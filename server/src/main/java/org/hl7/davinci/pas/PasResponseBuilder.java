package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.SimpleQuantity;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * Constructs PAS-conformant ClaimResponse bundles and inquiry response Parameters.
 * Handles correct extension placement (reviewAction on adjudication), item sequence echoing,
 * and bundle wrapping per the PAS IG profiles.
 */
@Component
public class PasResponseBuilder {

  private static final Logger log = LoggerFactory.getLogger(PasResponseBuilder.class);

  private final AtomicLong authCounter = new AtomicLong(0);
  private final AtomicLong pendCounter = new AtomicLong(0);
  private final AtomicLong traceCounter = new AtomicLong(0);
  private final String serverBase;
  private final DaoRegistry daoRegistry;
  private final PasQuestionnaireAddresses questionnaireAddresses;

  public PasResponseBuilder(AppProperties appProperties, DaoRegistry daoRegistry) {
    this.serverBase = FhirUtil.normalizeServerBase(appProperties.getServer_address());
    this.daoRegistry = daoRegistry;
    this.questionnaireAddresses = new PasQuestionnaireAddresses(appProperties, daoRegistry);
  }

  /**
   * Readies a response Bundle for the provider: documentation requests name their questionnaire
   * the way providers see it (see {@link PasQuestionnaireAddresses}). The Bundle's resources must
   * be copies, not the stored instances.
   */
  public Bundle presentToProvider(Bundle bundle) {
    if (bundle != null) {
      for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
        if (entry.getResource() instanceof CommunicationRequest request) {
          questionnaireAddresses.presentToProvider(request);
        }
      }
    }
    return bundle;
  }

  /** The canonical a questionnaire reference from a provider stands for. */
  public String questionnaireCanonical(String reference) {
    return questionnaireAddresses.canonicalOf(reference);
  }

  /**
   * Builds a PAS Response Bundle from a submitted Claim and per-item coverage decisions.
   *
   * @param requestClaim the original Claim from the request bundle
   * @param requestBundle the full request bundle (for resource references)
   * @param itemDecisions map of Claim.item.sequence -> CoverageDecision
   * @param authNumberPrefix prefix for generated authorization numbers
   * @return a PAS Response Bundle with ClaimResponse as the first entry
   */
  public Bundle buildSubmitResponse(Claim requestClaim, Bundle requestBundle,
      Map<Integer, CoverageDecision> itemDecisions, String authNumberPrefix) {

    ClaimResponse claimResponse = buildClaimResponse(requestClaim, requestBundle, itemDecisions, authNumberPrefix);
    Bundle responseBundle = wrapInResponseBundle(claimResponse, requestBundle);

    // Per PAS IG, pended items needing documentation get a CommunicationRequest referenced
    // from ClaimResponse.communicationRequest; the provider builds the Task locally.
    addCommunicationRequests(responseBundle, requestClaim, claimResponse, itemDecisions,
        authNumberPrefix);

    return responseBundle;
  }

  /**
   * Builds a Parameters resource wrapping each matching ClaimResponse in a "return" parameter,
   * the output parameter of the PAS Claim/$inquire OperationDefinition.
   * Per PAS IG narrative/examples for $inquire output.
   */
  public Parameters buildInquiryResponse(List<ClaimResponse> matches) {
    Parameters params = new Parameters();
    for (ClaimResponse cr : matches) {
      ClaimResponse sanitized = sanitizeForBundle(cr);
      Bundle responseBundle = wrapInResponseBundle(sanitized);
      responseBundle.getMeta().getProfile().clear();
      responseBundle.getMeta().addProfile(PasConstants.PROFILE_PAS_INQUIRY_RESPONSE_BUNDLE);
      Function<Reference, Resource> resolver = ref -> readReferencedResource(ref, daoRegistry);
      addReferencedResources(responseBundle, sanitized, resolver);
      addReferencedCommunicationRequests(responseBundle, sanitized, resolver);
      addSupportingResources(responseBundle, sanitized, resolver);
      presentToProvider(responseBundle);
      params.addParameter().setName("return").setResource(responseBundle);
    }
    return params;
  }

  /**
   * Finalizes pended adjudications in-place, transitioning A4 to A1 (Certified).
   */
  public void resolvePendedItems(ClaimResponse claimResponse, String authNumberPrefix) {
    finalizePendedItems(claimResponse, REVIEW_CODE_A1, "Certified in total", authNumberPrefix);
  }

  /**
   * Finalizes pended adjudications in-place, transitioning A4 to A6 (Modified).
   */
  public void modifyPendedItems(ClaimResponse claimResponse, String authNumberPrefix) {
    finalizePendedItems(claimResponse, REVIEW_CODE_A6, "Modified", authNumberPrefix);
  }

  /**
   * Applies coverage decisions to an existing ClaimResponse in-place. For existing items
   * with a matching decision, replaces the reviewAction extension. For decisions targeting
   * sequences not present in the CR, adds new response items.
   */
  public void applyItemDecisions(ClaimResponse claimResponse,
      Map<Integer, CoverageDecision> itemDecisions, String authNumberPrefix) {

    // Track which decision sequences were applied to existing items
    java.util.Set<Integer> appliedSequences = new java.util.HashSet<>();

    for (ClaimResponse.ItemComponent item : claimResponse.getItem()) {
      int seq = item.getItemSequence();
      CoverageDecision decision = itemDecisions.get(seq);
      if (decision == null) {
        continue;
      }
      appliedSequences.add(seq);

      // Preserve existing auth number -- it is a stable tracking identifier
      String existingAuth = PasExtensions.extractAuthorizationNumber(item);

      // Replace reviewAction on existing adjudication
      for (ClaimResponse.AdjudicationComponent adj : item.getAdjudication()) {
        adj.getExtension().removeIf(e -> PasConstants.REVIEW_ACTION.equals(e.getUrl()));

        boolean isApproved = REVIEW_CODE_A1.equals(decision.reviewActionCode());
        boolean includeAuthNumber = requiresAuthorizationNumber(decision.reviewActionCode());
        String authNumber = null;
        if (includeAuthNumber) {
          // Reuse existing auth number if present; only generate new if none exists
          authNumber = existingAuth != null
              ? existingAuth
              : nextAuthorizationNumber(authNumberPrefix);
        }
        adj.addExtension(PasExtensions.buildReviewActionExtension(
            decision.reviewActionCode(), decision.reviewActionDisplay(), authNumber));

        // Manage preAuthPeriod: add for approved, remove for non-approved
        if (isApproved && item.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD) == null) {
          item.addExtension(buildDefaultPreAuthPeriod());
        } else if (!isApproved) {
          item.getExtension().removeIf(e -> PasConstants.ITEM_PREAUTH_PERIOD.equals(e.getUrl()));
        }
      }

      // Manage adminRefNumber: add for pended items, remove for non-pended
      if (REVIEW_CODE_A4.equals(decision.reviewActionCode())) {
        if (item.getExtensionByUrl(PasConstants.ADMIN_REF_NUMBER) == null) {
          String adminRef = authNumberPrefix + "PEND" + String.format("%04d", pendCounter.incrementAndGet());
          item.addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType(adminRef));
        }
      } else {
        item.getExtension().removeIf(e -> PasConstants.ADMIN_REF_NUMBER.equals(e.getUrl()));
      }
    }

    // Add new items for decisions that didn't match existing items
    for (Map.Entry<Integer, CoverageDecision> entry : itemDecisions.entrySet()) {
      if (appliedSequences.contains(entry.getKey())) {
        continue;
      }

      CoverageDecision decision = entry.getValue();
      ClaimResponse.ItemComponent newItem = claimResponse.addItem();
      newItem.setItemSequence(entry.getKey());

      ClaimResponse.AdjudicationComponent adj = newItem.addAdjudication();
      adj.setCategory(new CodeableConcept().addCoding(
          new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));

      boolean isApproved = REVIEW_CODE_A1.equals(decision.reviewActionCode());
      boolean includeAuthNumber = requiresAuthorizationNumber(decision.reviewActionCode());
      String authNumber = includeAuthNumber
          ? nextAuthorizationNumber(authNumberPrefix)
          : null;
      adj.addExtension(PasExtensions.buildReviewActionExtension(
          decision.reviewActionCode(), decision.reviewActionDisplay(), authNumber));

      if (isApproved) {
        newItem.addExtension(buildDefaultPreAuthPeriod());
      }

      if (REVIEW_CODE_A4.equals(decision.reviewActionCode())) {
        String adminRef = authNumberPrefix + "PEND" + String.format("%04d", pendCounter.incrementAndGet());
        newItem.addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType(adminRef));
      }
    }

    for (ClaimResponse.ItemComponent item : claimResponse.getItem()) {
      CoverageDecision decision = itemDecisions.get(item.getItemSequence());
      if (decision != null) {
        noteDecision(claimResponse, item, decision);
      }
    }
    refreshDecisionSummary(claimResponse);
  }

  /**
   * Builds a PAS Response Bundle for subscription notifications. Resolves referenced
   * resources (Patient, Insurer, Requestor) from the database rather than a request bundle.
   */
  public Bundle buildNotificationResponseBundle(ClaimResponse claimResponse, DaoRegistry daoRegistry) {
    ClaimResponse sanitized = sanitizeForBundle(claimResponse);
    // The communicatedDiagnosis extension is sliced onto ClaimResponse.item by the PAS profile but
    // its own context does not list that element, and notification validators reject it there.
    // A decision update does not need the diagnosis echo, so it stays on $submit replies only.
    for (ClaimResponse.ItemComponent item : sanitized.getItem()) {
      item.getExtension().removeIf(e -> PasConstants.EXT_COMMUNICATED_DIAGNOSIS.equals(e.getUrl()));
    }
    Bundle bundle = wrapInResponseBundle(sanitized);

    Function<Reference, Resource> resolver = ref -> readReferencedResource(ref, daoRegistry);
    addReferencedResources(bundle, sanitized, resolver);
    addReferencedCommunicationRequests(bundle, sanitized, resolver);

    presentToProvider(bundle);
    return bundle;
  }

  private Resource readReferencedResource(Reference ref, DaoRegistry daoRegistry) {
    if (ref == null || !ref.hasReference()) {
      return null;
    }

    try {
      IdType refId = new IdType(ref.getReference());
      String resourceType = refId.getResourceType();
      if (resourceType == null || resourceType.isBlank()) {
        return null;
      }

      var dao = daoRegistry.getResourceDao(resourceType);
      return dao == null ? null : (Resource) dao.read(refId, new SystemRequestDetails());
    } catch (ResourceNotFoundException e) {
      // Referenced resource no longer exists; skip it
      return null;
    }
  }

  // ===== Internal =====

  /**
   * Resolves an Organization reference from a bundle and returns its first NPI value, or null.
   */
  private String resolveNpiFromBundle(Reference ref, Bundle bundle) {
    String npi = PasBundleReferenceResolver.findOrganizationNpiInBundle(bundle, ref);
    if (npi != null || ref == null || !ref.hasReference()) {
      return npi;
    }
    // By the time the response is built the Claim points at the payer's stored Organization,
    // which is no longer findable in the request Bundle under that reference.
    return readReferencedResource(ref, daoRegistry) instanceof Organization organization
        ? PasBundleReferenceResolver.extractNpiValue(organization)
        : null;
  }

  private void finalizePendedItems(ClaimResponse claimResponse,
      String targetCode, String targetDisplay, String authNumberPrefix) {
    for (ClaimResponse.ItemComponent item : claimResponse.getItem()) {
      boolean itemWasFinalized = false;
      for (ClaimResponse.AdjudicationComponent adj : item.getAdjudication()) {
        if (!isPendedReviewAction(adj)) {
          continue;
        }

        adj.getExtension().removeIf(e -> PasConstants.REVIEW_ACTION.equals(e.getUrl()));
        String authNumber = nextAuthorizationNumber(authNumberPrefix);
        adj.addExtension(PasExtensions.buildReviewActionExtension(targetCode, targetDisplay, authNumber));
        itemWasFinalized = true;
      }

      if (itemWasFinalized) {
        item.getExtension().removeIf(e -> PasConstants.ADMIN_REF_NUMBER.equals(e.getUrl()));
        if (item.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD) == null) {
          item.addExtension(buildDefaultPreAuthPeriod());
        }
        noteDecision(claimResponse, item,
            new CoverageDecision(targetCode, targetDisplay, false));
      }
    }
    refreshDecisionSummary(claimResponse);

    // Update outcome: if no pended items remain, mark as complete
    boolean stillPended = claimResponse.getItem().stream()
        .flatMap(item -> item.getAdjudication().stream())
        .anyMatch(this::isPendedReviewAction);
    if (!stillPended) {
      claimResponse.setOutcome(ClaimResponse.RemittanceOutcome.COMPLETE);
    }
  }

  /**
   * Identifiers, reviewer and transmission identifiers shared by every ClaimResponse built for a
   * request. The payer's own trace number comes first; the provider's trace numbers for the request
   * are echoed after it, as X12 278 responses return the requester's TRN.
   */
  private void addHeader(ClaimResponse cr, Claim requestClaim, Bundle requestBundle) {
    // CR-level identifier (PASIdentifier profile)
    cr.addIdentifier(new Identifier()
        .setSystem(identifierSystem("patient-event-trace-number"))
        .setValue(UUID.randomUUID().toString()));
    for (Identifier requestIdentifier : requestClaim.getIdentifier()) {
      Identifier echoed = requestIdentifier.copy();
      echoed.getExtension().removeIf(e -> !PasConstants.IDENTIFIER_SUB_DEPARTMENT.equals(e.getUrl())
          && !PasConstants.IDENTIFIER_JURISDICTION.equals(e.getUrl()));
      cr.addIdentifier(echoed);
    }

    // Decisions are made by rule, not by a human reviewer
    Extension reviewer = new Extension(PasConstants.CLAIM_RESPONSE_REVIEWER);
    reviewer.addExtension("wasHumanReviewedFlag", new BooleanType(false));
    cr.addExtension(reviewer);

    // Reverse TransmissionIdentifiers for the response (payer is now sender, provider is receiver)
    Extension requestTransmissionIds = PasExtensions.extractTransmissionIdentifiers(requestClaim);
    if (requestTransmissionIds != null) {
      String requestSender = PasExtensions.extractApplicationSenderCode(requestClaim);
      String requestReceiver = PasExtensions.extractApplicationReceiverCode(requestClaim);
      cr.addExtension(PasExtensions.buildTransmissionIdentifiersExtension(
          requestReceiver != null ? requestReceiver : "",
          requestSender != null ? requestSender : ""));
    } else {
      String providerNpi = resolveNpiFromBundle(requestClaim.getProvider(), requestBundle);
      String insurerNpi = resolveNpiFromBundle(requestClaim.getInsurer(), requestBundle);
      if (providerNpi != null || insurerNpi != null) {
        cr.addExtension(PasExtensions.buildTransmissionIdentifiersExtension(
            insurerNpi != null ? insurerNpi : "",
            providerNpi != null ? providerNpi : ""));
      }
    }
  }

  private ClaimResponse buildClaimResponse(Claim requestClaim, Bundle requestBundle,
      Map<Integer, CoverageDecision> itemDecisions, String authNumberPrefix) {

    ClaimResponse cr = new ClaimResponse();
    cr.setId(UUID.randomUUID().toString());
    cr.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_RESPONSE);

    cr.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);
    cr.setType(requestClaim.getType().copy());
    cr.setUse(ClaimResponse.Use.PREAUTHORIZATION);
    cr.setPatient(requestClaim.getPatient().copy());
    cr.setCreated(new Date());
    cr.setInsurer(requestClaim.getInsurer().copy());
    cr.setRequestor(requestClaim.getProvider().copy());
    // Outcome is always complete; a pend is conveyed by the item-level A4 reviewAction
    cr.setOutcome(ClaimResponse.RemittanceOutcome.COMPLETE);

    // Reference back to the original Claim
    String claimId = requestClaim.getIdElement().getIdPart();
    if (claimId != null) {
      cr.setRequest(new Reference("Claim/" + claimId));
    }

    addHeader(cr, requestClaim, requestBundle);

    Map<Integer, String> itemAuthNumbers = new HashMap<>();

    // Build response items echoing request item sequences
    for (Claim.ItemComponent requestItem : requestClaim.getItem()) {
      int seq = requestItem.getSequence();
      CoverageDecision decision = itemDecisions.getOrDefault(seq,
          new CoverageDecision(REVIEW_CODE_NA, "Not Required", false));

      ClaimResponse.ItemComponent responseItem = cr.addItem();
      responseItem.setItemSequence(seq);

      // itemTraceNumber is 0..*: retain the provider-echoed request trace(s). Payer traces for
      // documentation requests are synced from the persisted CommunicationRequests after dedup,
      // so items and requests always carry the same trace number on both create and update paths.
      for (Identifier traceId : PasExtensions.extractItemTraceNumbers(requestItem)) {
        responseItem.addExtension(PasExtensions.buildItemTraceNumberExtension(traceId));
      }

      // Adjudication with reviewAction extension
      ClaimResponse.AdjudicationComponent adj = responseItem.addAdjudication();
      adj.setCategory(new CodeableConcept().addCoding(
          new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));

      boolean isApproved = REVIEW_CODE_A1.equals(decision.reviewActionCode());
      boolean includeAuthNumber = requiresAuthorizationNumber(decision.reviewActionCode());
      String authNumber = null;
      if (includeAuthNumber) {
        authNumber = nextAuthorizationNumber(authNumberPrefix);
        itemAuthNumbers.put(seq, authNumber);
      }

      adj.addExtension(PasExtensions.buildReviewActionExtension(
          decision.reviewActionCode(), decision.reviewActionDisplay(), authNumber));

      // Approved items get additional extensions per PAS IG
      if (isApproved) {
        responseItem.addExtension(buildDefaultPreAuthPeriod());
        responseItem.addExtension(PasExtensions.buildPreAuthIssueDateExtension(new Date()));
      }

      // Echo requestedServiceDate from request item serviced[x]
      Type servicedValue = PasExtensions.extractServicedValue(requestItem);
      if (servicedValue != null) {
        responseItem.addExtension(PasExtensions.buildRequestedServiceDateExtension(servicedValue));
      }

      // Pended items get an administrationReferenceNumber for inquiry matching
      if (REVIEW_CODE_A4.equals(decision.reviewActionCode())) {
        String adminRef = authNumberPrefix + "PEND" + String.format("%04d", pendCounter.incrementAndGet());
        responseItem.addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType(adminRef));
      }

      echoLineContext(responseItem, requestItem, requestClaim);
      if (includeAuthNumber) {
        Reference authorized = authorizedParty(lineProvider(requestClaim, requestItem), requestBundle);
        if (authorized != null) {
          responseItem.addExtension(authorizedProviderExtension(authorized));
        }
        responseItem.addExtension(authorizedDetailExtension(requestItem, decision));
      }
      noteDecision(cr, responseItem, decision);
    }

    // addItem entries for A6 (Modified) decisions with modifications
    for (Map.Entry<Integer, CoverageDecision> entry : itemDecisions.entrySet()) {
      CoverageDecision decision = entry.getValue();
      if (REVIEW_CODE_A6.equals(decision.reviewActionCode()) && decision.hasModification()) {
        String authNumber = itemAuthNumbers.get(entry.getKey());
        if (authNumber == null) {
          authNumber = nextAuthorizationNumber(authNumberPrefix);
        }
        Claim.ItemComponent requestItem = requestClaim.getItem().stream()
            .filter(item -> item.getSequence() == entry.getKey())
            .findFirst()
            .orElse(null);
        addModifiedLine(cr, requestClaim, requestBundle, requestItem, entry.getKey(), decision, authNumber);
      }
    }

    refreshDecisionSummary(cr);

    return cr;
  }

  // ===== Decision detail =====

  /**
   * Carries the requested line's context onto its decision: the authorization number the
   * provider cited, the admission and discharge dates of the stay, and the line's diagnosis.
   */
  private void echoLineContext(ClaimResponse.ItemComponent responseItem, Claim.ItemComponent requestItem,
      Claim requestClaim) {
    Extension cited = requestItem.getExtensionByUrl(PasConstants.AUTHORIZATION_NUMBER);
    if (cited != null && cited.hasValue()) {
      responseItem.addExtension(cited.copy());
    }
    for (Extension stay : stayDates(requestClaim)) {
      responseItem.addExtension(stay);
    }
    CodeableConcept diagnosis = lineDiagnosis(requestClaim, requestItem);
    if (diagnosis != null) {
      responseItem.addExtension(PasConstants.EXT_COMMUNICATED_DIAGNOSIS, diagnosis);
    }
  }

  /** admissionDates / dischargeDate extensions from the Claim's supportingInfo, when it has them. */
  private static List<Extension> stayDates(Claim claim) {
    List<Extension> dates = new ArrayList<>();
    for (Claim.SupportingInformationComponent info : claim.getSupportingInfo()) {
      String category = info.getCategory().getCodingFirstRep().getCode();
      if ("admissionDates".equals(category) && info.hasTiming()) {
        dates.add(new Extension(PasConstants.ADMISSION_DATES, info.getTiming().copy()));
      } else if ("dischargeDates".equals(category) && info.getTiming() instanceof DateType date) {
        dates.add(new Extension(PasConstants.DISCHARGE_DATE, date.copy()));
      }
    }
    return dates;
  }

  /** The diagnosis the line points at through diagnosisSequence, else the Claim's first one. */
  private static CodeableConcept lineDiagnosis(Claim claim, Claim.ItemComponent item) {
    for (PositiveIntType sequence : item.getDiagnosisSequence()) {
      for (Claim.DiagnosisComponent diagnosis : claim.getDiagnosis()) {
        if (diagnosis.getSequence() == sequence.getValue() && diagnosis.hasDiagnosisCodeableConcept()) {
          return diagnosis.getDiagnosisCodeableConcept().copy();
        }
      }
    }
    return claim.getDiagnosisFirstRep().hasDiagnosisCodeableConcept()
        ? claim.getDiagnosisFirstRep().getDiagnosisCodeableConcept().copy()
        : null;
  }

  /** The care-team member the line names, else the requesting provider. */
  private static Reference lineProvider(Claim claim, Claim.ItemComponent item) {
    for (PositiveIntType sequence : item.getCareTeamSequence()) {
      for (Claim.CareTeamComponent member : claim.getCareTeam()) {
        if (member.getSequence() == sequence.getValue() && member.getProvider().hasReference()) {
          return member.getProvider().copy();
        }
      }
    }
    return claim.getProvider().copy();
  }

  /**
   * Authorizations name a Practitioner or an Organization. A PractitionerRole stands for the
   * Practitioner it names; anything that cannot be resolved that way is not named at all.
   */
  private Reference authorizedParty(Reference provider, Bundle requestBundle) {
    String reference = provider.getReference();
    if (reference == null) {
      return null;
    }
    if (!reference.contains("PractitionerRole/")
        && (reference.contains("Practitioner/") || reference.contains("Organization/"))) {
      return provider.copy();
    }
    PractitionerRole role = requestBundle != null
        ? ResourceResolver.findInBundle(reference, PractitionerRole.class, requestBundle)
        : null;
    if (role == null && readReferencedResource(provider, daoRegistry) instanceof PractitionerRole stored) {
      role = stored;
    }
    return role != null && role.getPractitioner().hasReference() ? role.getPractitioner().copy() : null;
  }

  private static Extension authorizedProviderExtension(Reference provider) {
    Extension authorized = new Extension(PasConstants.ITEM_AUTHORIZED_PROVIDER);
    authorized.addExtension("provider", provider);
    return authorized;
  }

  /** What was authorized for the line: the service, its modifiers, units and price. */
  private static Extension authorizedDetailExtension(Claim.ItemComponent requestItem,
      CoverageDecision decision) {
    Extension detail = new Extension(PasConstants.ITEM_AUTHORIZED_DETAIL);
    CodeableConcept service = decision.modifiedProductOrService() != null
        ? decision.modifiedProductOrService()
        : requestItem.getProductOrService();
    if (!service.isEmpty()) {
      detail.addExtension("productOrServiceCode", service.copy());
    }
    for (CodeableConcept modifier : requestItem.getModifier()) {
      detail.addExtension("modifier", modifier.copy());
    }
    if (requestItem.hasUnitPrice()) {
      detail.addExtension("unitPrice", requestItem.getUnitPrice().copy());
    }
    Quantity quantity = decision.modifiedQuantity() != null ? decision.modifiedQuantity() : requestItem.getQuantity();
    if (quantity != null && !quantity.isEmpty()) {
      // value[x] is Quantity; a SimpleQuantity would be written as the invalid valueSimpleQuantity
      Quantity authorized = new Quantity();
      quantity.copyValues(authorized);
      detail.addExtension("quantity", authorized);
    }
    return detail;
  }

  /**
   * A modified (A6) decision returns the line as authorized: the requested line with the payer's
   * change applied, carrying the request's line details, the authorization and its period.
   */
  private void addModifiedLine(ClaimResponse cr, Claim requestClaim, Bundle requestBundle,
      Claim.ItemComponent requestItem, int sequence, CoverageDecision decision, String authNumber) {
    ClaimResponse.AddedItemComponent addItem = cr.addAddItem();
    addItem.addItemSequence(sequence);

    if (requestItem != null) {
      for (Extension ext : requestItem.getExtension()) {
        if (ADDED_ITEM_ECHOED_EXTENSIONS.contains(ext.getUrl())) {
          addItem.addExtension(ext.copy());
        }
      }
      if (requestItem.hasRevenue()) {
        addItem.addExtension(PasConstants.REVENUE_CODE, requestItem.getRevenue().copy());
      }
      Reference provider = authorizedParty(lineProvider(requestClaim, requestItem), requestBundle);
      if (provider != null) {
        provider.addExtension(PasConstants.AUTHORIZED_PROVIDER_TYPE, new CodeableConcept().addCoding(
            new Coding(PasConstants.X12_ENTITY_IDENTIFIER_SYSTEM, "SJ", "Service Provider")));
        addItem.addProvider(provider);
      }
      addItem.setProductOrService(requestItem.getProductOrService().copy());
      requestItem.getModifier().forEach(modifier -> addItem.addModifier(modifier.copy()));
      if (requestItem.hasServiced()) {
        addItem.setServiced(requestItem.getServiced().copy());
      }
      if (requestItem.hasLocation()) {
        addItem.setLocation(requestItem.getLocation().copy());
      }
      if (requestItem.hasQuantity()) {
        addItem.setQuantity(requestItem.getQuantity().copy());
      }
      if (requestItem.hasUnitPrice()) {
        addItem.setUnitPrice(requestItem.getUnitPrice().copy());
      }
    }
    addItem.addExtension(PasExtensions.buildPreAuthIssueDateExtension(new Date()));
    addItem.addExtension(buildDefaultPreAuthPeriod());
    stayDates(requestClaim).forEach(addItem::addExtension);

    if (decision.modifiedProductOrService() != null) {
      addItem.setProductOrService(decision.modifiedProductOrService().copy());
    }
    if (decision.modifiedQuantity() != null) {
      SimpleQuantity modified = decision.modifiedQuantity();
      if (addItem.hasQuantity() && !modified.hasUnit() && !modified.hasCode()) {
        // A bare number changes how many of the requested unit are authorized
        addItem.getQuantity().setValue(modified.getValue());
      } else {
        addItem.setQuantity(modified.copy());
      }
    }

    ClaimResponse.AdjudicationComponent addItemAdj = addItem.addAdjudication();
    addItemAdj.setCategory(new CodeableConcept().addCoding(
        new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));
    addItemAdj.addExtension(PasExtensions.buildReviewActionExtension(
        REVIEW_CODE_A6, "Modified", authNumber));
  }

  /** Claim.item extensions that describe the service itself and so stay with a modified line. */
  private static final Set<String> ADDED_ITEM_ECHOED_EXTENSIONS = Set.of(
      PasConstants.ITEM_TRACE_NUMBER,
      PasConstants.AUTHORIZATION_NUMBER,
      PasConstants.ADMIN_REF_NUMBER,
      PasConstants.EPSDT_INDICATOR,
      PasConstants.NURSING_HOME_RESIDENTIAL_STATUS,
      PasConstants.NURSING_HOME_LEVEL_OF_CARE,
      PasConstants.PRODUCT_OR_SERVICE_CODE_END,
      PasConstants.REVENUE_UNIT_RATE_LIMIT,
      PasConstants.ITEM_REQUESTED_SERVICE,
      PasConstants.SERVICE_ITEM_REQUEST_TYPE,
      PasConstants.CERTIFICATION_TYPE);

  /**
   * Records why the line got its decision as a processNote and points the line at it. Earlier
   * notes stay as the history of the request; the line references the current one.
   */
  private static void noteDecision(ClaimResponse cr, ClaimResponse.ItemComponent item,
      CoverageDecision decision) {
    String text = "Line " + item.getItemSequence() + ": " + switch (decision.reviewActionCode()) {
      case REVIEW_CODE_A1 -> "certified in total.";
      case REVIEW_CODE_A3 -> "not certified.";
      case REVIEW_CODE_A4 -> decision.hasAdditionalDocumentationInfo()
          ? "pended; additional documentation has been requested."
          : "pended for review.";
      case REVIEW_CODE_A6 -> decision.modifiedQuantity() != null && decision.modifiedQuantity().hasValue()
          ? "certified with changes; " + decision.modifiedQuantity().getValue().toPlainString()
              + " unit(s) authorized, see the added item."
          : "certified with changes, see the added item.";
      default -> "prior authorization is not required.";
    };
    int number = cr.getProcessNote().size() + 1;
    cr.addProcessNote().setNumber(number).setText(text);
    item.getNoteNumber().clear();
    item.addNoteNumber(number);
  }

  /**
   * Keeps the header in step with the lines: the authorization number of the request (as the
   * header reviewAction, which carries only the number), the period covering the certified lines,
   * and the provider they are authorized for.
   */
  private static void refreshDecisionSummary(ClaimResponse cr) {
    String authorizationNumber = null;
    Reference authorizedProvider = null;
    List<Extension> periods = new ArrayList<>();
    for (ClaimResponse.ItemComponent item : cr.getItem()) {
      periods.addAll(item.getExtensionsByUrl(PasConstants.ITEM_PREAUTH_PERIOD));
      if (authorizationNumber == null) {
        authorizationNumber = PasExtensions.extractAuthorizationNumber(item);
      }
      Extension provider = item.getExtensionByUrl(PasConstants.ITEM_AUTHORIZED_PROVIDER);
      if (authorizedProvider == null && provider != null
          && provider.getExtensionByUrl("provider") != null
          && provider.getExtensionByUrl("provider").getValue() instanceof Reference ref) {
        authorizedProvider = ref.copy();
      }
    }
    for (ClaimResponse.AddedItemComponent addItem : cr.getAddItem()) {
      periods.addAll(addItem.getExtensionsByUrl(PasConstants.ITEM_PREAUTH_PERIOD));
    }

    cr.getAdjudication().clear();
    if (authorizationNumber != null) {
      Extension reviewAction = new Extension(PasConstants.REVIEW_ACTION);
      reviewAction.addExtension("number", new StringType(authorizationNumber));
      cr.addAdjudication()
          .setCategory(new CodeableConcept().addCoding(
              new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")))
          .addExtension(reviewAction);
    }

    Date start = null;
    Date end = null;
    for (Extension ext : periods) {
      if (ext.getValue() instanceof Period period) {
        if (period.hasStart() && (start == null || period.getStart().before(start))) start = period.getStart();
        if (period.hasEnd() && (end == null || period.getEnd().after(end))) end = period.getEnd();
      }
    }
    cr.setPreAuthPeriod(start != null || end != null ? new Period().setStart(start).setEnd(end) : null);

    cr.getExtension().removeIf(e -> PasConstants.ITEM_AUTHORIZED_PROVIDER.equals(e.getUrl()));
    if (authorizedProvider != null) {
      cr.addExtension(authorizedProviderExtension(authorizedProvider));
    }
  }

  // ===== Rejected requests =====

  /**
   * Response for a request the payer will not decide on: a ClaimResponse with outcome error and
   * one X12 AAA error per rejected line. No authorization (and no Claim) is stored for it, so
   * ClaimResponse.request carries a data-absent reason and the entry has a urn:uuid fullUrl.
   */
  public Bundle buildErrorResponse(Claim requestClaim, Bundle requestBundle,
      List<Claim.ItemComponent> rejectedItems) {
    ClaimResponse cr = new ClaimResponse();
    cr.setId(UUID.randomUUID().toString());
    cr.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_RESPONSE);
    cr.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);
    cr.setType(requestClaim.getType().copy());
    cr.setUse(ClaimResponse.Use.PREAUTHORIZATION);
    cr.setPatient(requestClaim.getPatient().copy());
    cr.setCreated(new Date());
    cr.setInsurer(requestClaim.getInsurer().copy());
    cr.setRequestor(requestClaim.getProvider().copy());
    cr.setOutcome(ClaimResponse.RemittanceOutcome.ERROR);
    cr.getRequest().addExtension(PasConstants.DATA_ABSENT_REASON, new CodeType("not-applicable"));
    addHeader(cr, requestClaim, requestBundle);

    for (Claim.ItemComponent item : rejectedItems) {
      int number = cr.getProcessNote().size() + 1;
      String code = item.getProductOrService().getCodingFirstRep().getCode();
      cr.addProcessNote().setNumber(number).setText("Line " + item.getSequence() + ": procedure code "
          + code + " is not accepted for prior authorization. Correct the code and resubmit.");

      ClaimResponse.ErrorComponent error = cr.addError();
      error.setItemSequence(item.getSequence());
      error.setCode(new CodeableConcept().addCoding(new Coding(PasConstants.X12_REJECT_REASON_SYSTEM,
          "AG", "Invalid/Missing Procedure Code(s)")));
      error.addExtension(PasConstants.ERROR_FOLLOWUP_ACTION, new CodeableConcept().addCoding(
          new Coding(PasConstants.X12_FOLLOWUP_ACTION_SYSTEM, "C", "Please Correct and Resubmit")));
      Extension element = new Extension(PasConstants.ERROR_ELEMENT);
      element.addExtension("processNote", new PositiveIntType(number));
      element.addExtension("error", new StringType("2000F-SV101"));
      error.addExtension(element);
      error.addExtension(PasConstants.ERROR_PATH, new StringType(
          "Bundle.entry[0].Claim.item[" + requestClaim.getItem().indexOf(item) + "].productOrService"));
    }

    Bundle bundle = wrapInResponseBundle(cr, requestBundle);
    bundle.getEntryFirstRep().setFullUrl("urn:uuid:" + cr.getIdElement().getIdPart());
    return bundle;
  }

  Bundle wrapInResponseBundle(ClaimResponse claimResponse) {
    return wrapInResponseBundle(claimResponse, null);
  }

  Bundle wrapInResponseBundle(ClaimResponse claimResponse, Bundle requestBundle) {
    Bundle bundle = new Bundle();
    bundle.getMeta().addProfile(PasConstants.PROFILE_PAS_RESPONSE_BUNDLE);
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.setTimestamp(new Date());
    bundle.setIdentifier(new Identifier()
        .setSystem(identifierSystem("submitter-transaction-identifier"))
        .setValue(UUID.randomUUID().toString()));

    String idPart = extractClaimResponseIdPart(claimResponse);
    if (idPart == null || idPart.isBlank()) {
      idPart = UUID.randomUUID().toString();
      claimResponse.setId(idPart);
    }

    Bundle.BundleEntryComponent entry = bundle.addEntry()
        .setResource(claimResponse);

    String fullUrl = FhirUtil.buildVersionlessResourceUrl(serverBase, "ClaimResponse", idPart);
    if (fullUrl != null) {
      entry.setFullUrl(fullUrl);
    }

    if (requestBundle != null) {
      addReferencedResources(bundle, claimResponse,
          ref -> ResourceResolver.findInBundle(ref.getReference(), Resource.class, requestBundle));
    }

    return bundle;
  }

  /**
   * Adds resources referenced by the ClaimResponse (patient, insurer, requestor)
   * from the request bundle into the response bundle, using server-base fullUrls
   * so bundle resolution rules match the relative references on the ClaimResponse.
   */
  private void addReferencedResources(Bundle responseBundle, ClaimResponse cr,
      Function<Reference, Resource> resourceResolver) {
    Set<String> addedKeys = new HashSet<>();
    addReferencedResource(responseBundle, cr.getPatient(), resourceResolver, addedKeys);
    addReferencedResource(responseBundle, cr.getInsurer(), resourceResolver, addedKeys);
    addReferencedResource(responseBundle, cr.getRequestor(), resourceResolver, addedKeys);
  }

  /**
   * Adds the CommunicationRequests referenced by ClaimResponse.communicationRequest into the
   * response bundle so inquiry/notification bundles satisfy spec-61's resolvable-reference rule.
   */
  private void addReferencedCommunicationRequests(Bundle responseBundle, ClaimResponse cr,
      Function<Reference, Resource> resourceResolver) {
    Set<String> addedKeys = new HashSet<>();
    // PAS CommunicationRequest status is fixed to active: a request that has been answered or
    // withdrawn is no longer an outstanding ask, so it is left out along with the reference to it.
    cr.getCommunicationRequest().removeIf(ref ->
        resourceResolver.apply(ref) instanceof CommunicationRequest request && isClosed(request));
    for (Reference ref : cr.getCommunicationRequest()) {
      addReferencedResource(responseBundle, ref, resourceResolver, addedKeys);
    }
  }

  private static boolean isClosed(CommunicationRequest request) {
    return request.getStatus() == CommunicationRequest.CommunicationRequestStatus.COMPLETED
        || request.getStatus() == CommunicationRequest.CommunicationRequestStatus.REVOKED
        || request.getStatus() == CommunicationRequest.CommunicationRequestStatus.ENTEREDINERROR;
  }

  /**
   * Adds what else the authorization points at, so an inquiry Bundle is self-contained: any
   * other resource the ClaimResponse references (such as an authorized or added-item provider),
   * the Practitioner behind an included PractitionerRole, and the Tasks raised for its Claim.
   */
  private void addSupportingResources(Bundle responseBundle, ClaimResponse cr,
      Function<Reference, Resource> resourceResolver) {
    Set<String> addedKeys = new HashSet<>();
    for (Bundle.BundleEntryComponent entry : responseBundle.getEntry()) {
      addedKeys.add(resourceKey(entry.getResource()));
    }

    for (Reference ref : FhirContext.forR4Cached().newTerser()
        .getAllPopulatedChildElementsOfType(cr, Reference.class)) {
      // The stored Claim is not part of a response Bundle
      if (ref != cr.getRequest()) {
        addReferencedResource(responseBundle, ref, resourceResolver, addedKeys);
      }
    }

    for (Bundle.BundleEntryComponent entry : List.copyOf(responseBundle.getEntry())) {
      if (entry.getResource() instanceof PractitionerRole role) {
        addReferencedResource(responseBundle, role.getPractitioner(), resourceResolver, addedKeys);
      }
    }

    String claimReference = ResourceResolver.toVersionlessTypedReference(cr.getRequest(), "Claim");
    if (claimReference == null || !cr.getPatient().hasReference()) {
      return;
    }
    SearchParameterMap params = new SearchParameterMap();
    params.setLoadSynchronous(true);
    params.add("patient", new ReferenceParam(cr.getPatient().getReference()));
    for (Task task : daoRegistry.getResourceDao(Task.class)
        .searchForResources(params, new SystemRequestDetails())) {
      // PAS Task.reasonReference names the Claim the request for information is about
      if (claimReference.equals(
          ResourceResolver.toVersionlessTypedReference(task.getReasonReference(), "Claim"))) {
        addReferencedResource(responseBundle, new Reference(resourceKey(task)), ref -> task, addedKeys);
      }
    }
  }

  private static String resourceKey(Resource resource) {
    return resource.fhirType() + "/" + resource.getIdElement().getIdPart();
  }

  private void addReferencedResource(Bundle responseBundle, Reference ref,
      Function<Reference, Resource> resourceResolver, Set<String> addedKeys) {
    if (ref == null || !ref.hasReference()) {
      return;
    }

    String reference = ref.getReference();
    if (addedKeys.contains(reference)) {
      return;
    }

    Resource resource = resourceResolver.apply(ref);
    if (resource == null) {
      return;
    }

    addedKeys.add(reference);
    Bundle.BundleEntryComponent responseEntry = responseBundle.addEntry()
        .setResource(resource);
    String resourceType = resource.getResourceType().name();
    String idPart = resource.getIdElement().getIdPart();
    String fullUrl = FhirUtil.buildVersionlessResourceUrl(serverBase, resourceType, idPart);
    if (fullUrl != null) {
      responseEntry.setFullUrl(fullUrl);
    }
  }

  /** Namespace for the identifiers this payer assigns, rooted at its own base URL. */
  private String identifierSystem(String name) {
    return serverBase + "/identifier/" + name;
  }

  private static boolean requiresAuthorizationNumber(String reviewActionCode) {
    return REVIEW_CODE_A1.equals(reviewActionCode) || REVIEW_CODE_A6.equals(reviewActionCode);
  }

  private String nextAuthorizationNumber(String authNumberPrefix) {
    return authNumberPrefix + String.format("%04d", authCounter.incrementAndGet());
  }

  private boolean isPendedReviewAction(ClaimResponse.AdjudicationComponent adjudication) {
    Extension reviewAction = adjudication.getExtensionByUrl(PasConstants.REVIEW_ACTION);
    if (reviewAction == null) {
      return false;
    }

    Extension codeExt = reviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    if (codeExt == null || !(codeExt.getValue() instanceof CodeableConcept codeableConcept)) {
      return false;
    }

    return codeableConcept.getCoding().stream()
        .anyMatch(coding -> REVIEW_CODE_A4.equals(coding.getCode()));
  }

  private Extension buildDefaultPreAuthPeriod() {
    Date now = new Date();
    Calendar cal = Calendar.getInstance();
    cal.setTime(now);
    cal.add(Calendar.MONTH, 1);
    Date oneMonthLater = cal.getTime();

    Period period = new Period();
    period.setStart(now);
    period.setEnd(oneMonthLater);
    return new Extension(PasConstants.ITEM_PREAUTH_PERIOD, period);
  }

  private ClaimResponse sanitizeForBundle(ClaimResponse source) {
    ClaimResponse copy = source.copy();
    String idPart = extractClaimResponseIdPart(copy);
    if (idPart != null && !idPart.isBlank()) {
      copy.setId(idPart);
    }

    return copy;
  }

  private String extractClaimResponseIdPart(ClaimResponse claimResponse) {
    String idPart = claimResponse.getIdElement().getIdPart();
    if (idPart != null && !idPart.isBlank()) {
      return idPart;
    }

    String rawId = claimResponse.getId();
    if (rawId == null || rawId.isBlank()) {
      return null;
    }

    String parsed = new IdType(rawId).getIdPart();
    if (parsed != null && !parsed.isBlank()) {
      return parsed;
    }
    return rawId;
  }

  /**
   * Per the PAS IG, when a payer pends a request and needs additional documentation, the
   * response bundle carries a CommunicationRequest per pended item, referenced from
   * ClaimResponse.communicationRequest. The provider builds its own Task from these.
   *
   * @see <a href="https://hl7.org/fhir/us/davinci-pas/2.2.1/en/additionalinfo.html">
   *   PAS IG: Request for Additional Information</a>
   */
  void addCommunicationRequests(Bundle responseBundle, Claim requestClaim,
      ClaimResponse claimResponse, Map<Integer, CoverageDecision> itemDecisions,
      String authNumberPrefix) {
    String patientRef = requestClaim.hasPatient() ? requestClaim.getPatient().getReference() : null;

    for (Map.Entry<Integer, CoverageDecision> entry : itemDecisions.entrySet()) {
      CoverageDecision decision = entry.getValue();
      if (!REVIEW_CODE_A4.equals(decision.reviewActionCode())) continue;
      if (!decision.hasAdditionalDocumentationInfo()) continue;

      int lineNumber = entry.getKey();
      // payload is 0..1: emit one 102089-0 CommunicationRequest per questionnaire canonical.
      // Each gets a freshly minted unique trace number (X12 TRN02); when a pended update reuses
      // an equivalent open request during persistence, the original trace number survives.
      for (String questionnaireCanonical : decision.questionnaireUrls()) {
        addCommunicationRequest(responseBundle, claimResponse,
            PasCommunicationRequestBuilder.addressFromClaim(
                PasCommunicationRequestBuilder.buildQuestionnaireRequest(lineNumber, patientRef,
                    nextTraceNumber(authNumberPrefix), questionnaireCanonical),
                requestClaim));
      }
      for (String code : decision.requestedAttachmentCodes()) {
        CommunicationRequest request = PasCommunicationRequestBuilder.addressFromClaim(
            PasCommunicationRequestBuilder.buildAttachmentCodeRequest(lineNumber, code, patientRef),
            requestClaim);
        addCommunicationRequest(responseBundle, claimResponse, request);
        // The same ask as a PAS Task, which tracks it until the document arrives through CDex
        Task task = PasTaskBuilder.buildAttachmentRequest(lineNumber, code, requestClaim,
            resolveNpiFromBundle(requestClaim.getInsurer(), responseBundle),
            resolveNpiFromBundle(requestClaim.getProvider(), responseBundle), serverBase);
        PasTaskBuilder.setTraceNumber(task, request.getIdentifierFirstRep());
        responseBundle.addEntry().setFullUrl("urn:uuid:" + task.getId()).setResource(task);
      }
    }
  }

  private String nextTraceNumber(String authNumberPrefix) {
    return authNumberPrefix + "TRN" + String.format("%04d", traceCounter.incrementAndGet());
  }

  private void addCommunicationRequest(Bundle responseBundle, ClaimResponse claimResponse,
      CommunicationRequest communicationRequest) {
    String fullUrl = "urn:uuid:" + communicationRequest.getId();
    responseBundle.addEntry().setFullUrl(fullUrl).setResource(communicationRequest);
    claimResponse.addCommunicationRequest(new Reference(fullUrl));
  }
}
