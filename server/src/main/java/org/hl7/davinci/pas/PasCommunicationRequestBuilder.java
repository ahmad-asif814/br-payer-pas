package org.hl7.davinci.pas;

import java.util.UUID;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

public final class PasCommunicationRequestBuilder {

  private PasCommunicationRequestBuilder() {
  }

  /**
   * Questionnaire request: payload.content is the LOINC {@code 102089-0} marker; the identifier is
   * a payer-assigned unique trace number matching the itemTraceNumber on the pended item, which
   * satisfies profile-communicationrequest's IdentifierUnlessVO invariant and doubles as the DTR
   * context id. The requested questionnaire is recorded in a payer-internal extension since
   * payload.content is constrained to the 1..1 marker string.
   */
  public static CommunicationRequest buildQuestionnaireRequest(int lineNumber, String patientRef,
      String trn, String questionnaireCanonical) {
    CommunicationRequest request = base(lineNumber, patientRef, trn);
    request.addPayload().setContent(new StringType(PasConstants.LOINC_QUESTIONNAIRE_REQUEST));
    request.addExtension(PasConstants.EXT_REQUESTED_QUESTIONNAIRE,
        new CanonicalType(questionnaireCanonical));
    return request;
  }

  /**
   * Builds an attachment-code additional-information request (one LOINC/PWK code per request),
   * qualified by the payer's attachment modifier.
   */
  public static CommunicationRequest buildAttachmentCodeRequest(int lineNumber, String code,
      String patientRef) {
    CommunicationRequest request = base(lineNumber, patientRef, UUID.randomUUID().toString());
    request.addPayload().setContent(new StringType(code)).addExtension(contentModifierExtension());
    return request;
  }

  /** The contentModifier qualifying a requested document. */
  public static Extension contentModifierExtension() {
    return new Extension(PasConstants.EXT_CONTENT_MODIFIER, new CodeableConcept().addCoding(
        new Coding(PasConstants.LOINC_SYSTEM, PasConstants.LOINC_ATTACHMENT_MODIFIER, null)));
  }

  /**
   * Fills in who the request is between and how to answer it, from the Claim it is about: the
   * insurer asks, the requesting provider is to send the information back through CDex. The
   * Claim's principal diagnosis, when it has one, is carried as the diagnosis the request
   * relates to.
   */
  public static CommunicationRequest addressFromClaim(CommunicationRequest request, Claim claim) {
    request.addMedium(new CodeableConcept().addCoding(new Coding(
        PasConstants.PAS_TEMP_CODES_SYSTEM, PasConstants.MEDIUM_CDEX, null)));
    if (claim.getInsurer().hasReference()) {
      request.setRequester(claim.getInsurer().copy());
    }
    if (claim.getProvider().hasReference()) {
      request.addRecipient(claim.getProvider().copy());
      request.setSender(claim.getProvider().copy());
    }
    if (request.hasPayload() && claim.getDiagnosisFirstRep().hasDiagnosisCodeableConcept()) {
      request.getPayloadFirstRep().addExtension(PasConstants.EXT_COMMUNICATED_DIAGNOSIS,
          claim.getDiagnosisFirstRep().getDiagnosisCodeableConcept().copy());
    }
    return request;
  }

  /** Shared CommunicationRequest slices; the caller adds the single payload (payload is 0..1). */
  private static CommunicationRequest base(int lineNumber, String patientRef, String identifierValue) {
    CommunicationRequest request = new CommunicationRequest();
    request.setId("cr-" + UUID.randomUUID().toString().substring(0, 8));
    request.setMeta(new Meta().addProfile(PasConstants.PROFILE_PAS_COMMUNICATION_REQUEST));
    request.setStatus(CommunicationRequest.CommunicationRequestStatus.ACTIVE);
    request.addIdentifier(new Identifier()
        .setSystem(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM).setValue(identifierValue));
    request.addExtension(PasConstants.EXT_SERVICE_LINE_NUMBER, new PositiveIntType(lineNumber));
    request.addCategory(new CodeableConcept().addCoding(new Coding(
        PasConstants.X12_REQUEST_CATEGORY_SYSTEM, "15", "Justification for Admissions")));
    if (patientRef != null) {
      request.setSubject(new Reference(patientRef));
    }
    return request;
  }
}
