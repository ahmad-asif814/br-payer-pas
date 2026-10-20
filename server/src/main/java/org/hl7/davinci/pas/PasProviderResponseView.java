package org.hl7.davinci.pas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;

import ca.uhn.fhir.context.FhirContext;

/**
 * Expresses a PAS $submit response in the provider's terms.
 *
 * The payer stores the request's Patient, Organizations, etc. under its own ids, and the
 * persisted ClaimResponse, CommunicationRequests and Tasks point at those. The PAS IG instead
 * expects the response Bundle to echo the provider's resources exactly as sent (same fullUrl, id
 * and identifiers) with every reference resolvable inside the Bundle. This builds that view on a
 * copy, leaving the stored resources untouched.
 */
final class PasProviderResponseView {

  private static final FhirContext R4_CTX = FhirContext.forR4Cached();

  private PasProviderResponseView() {
  }

  /**
   * @param response the response Bundle as persisted (payer-side references)
   * @param originalRequest the request Bundle as received, before any reference rewriting
   * @param providerToPayerRefs provider reference -> payer reference, as rewritten on submit
   * @return a copy of the response expressed in the provider's references
   */
  static Bundle forSubmit(Bundle response, Bundle originalRequest, Map<String, String> providerToPayerRefs) {
    if (response == null || !response.hasEntry()) {
      return response;
    }
    Bundle view = response.copy();
    for (Bundle.BundleEntryComponent entry : view.getEntry()) {
      if (entry.getResource() instanceof ClaimResponse claimResponse) {
        // Scheduler bookkeeping, not part of the authorization
        claimResponse.getMeta().getTag().removeIf(tag ->
            PasConstants.PENDED_TAG_SYSTEM.equals(tag.getSystem()));
      }
    }
    if (originalRequest == null || !originalRequest.hasEntry()) {
      return view;
    }

    // Provider resources are re-added below from the untouched request; anything else in the
    // persisted response is the payer-side copy of them.
    view.getEntry().removeIf(entry -> !isPayerAuthored(entry.getResource()));

    // The Claim's own reference is recorded first, so it wins over a bundle-local alias.
    Map<String, String> payerToProviderRefs = new HashMap<>();
    if (providerToPayerRefs != null) {
      providerToPayerRefs.forEach((provider, payer) -> payerToProviderRefs.putIfAbsent(payer, provider));
    }

    List<Bundle.BundleEntryComponent> payerEntries = new ArrayList<>(view.getEntry());
    Set<Bundle.BundleEntryComponent> echoedSources = new HashSet<>();
    Deque<Bundle.BundleEntryComponent> toFollow = new ArrayDeque<>();

    for (Bundle.BundleEntryComponent entry : payerEntries) {
      for (Reference ref : referencesOf(entry.getResource())) {
        if (isClaimResponseRequest(entry.getResource(), ref)) {
          continue;
        }
        String value = ref.getReference();
        Bundle.BundleEntryComponent payerTarget = findEntry(payerEntries, value, baseOf(entry));
        if (payerTarget != null) {
          // A relative reference would otherwise be resolved against whichever base the reader
          // picks; the entry's own fullUrl is unambiguous.
          if (payerTarget.hasFullUrl()) {
            ref.setReference(payerTarget.getFullUrl());
          }
          continue;
        }
        String providerRef = payerToProviderRefs.getOrDefault(value, value);
        Bundle.BundleEntryComponent source = findEntry(originalRequest.getEntry(), providerRef, null);
        if (source == null) {
          continue;
        }
        ref.setReference(source.hasFullUrl() ? source.getFullUrl() : providerRef);
        if (echoedSources.add(source)) {
          toFollow.add(echo(view, source));
        }
      }
    }

    // Echoed resources keep their own references; whatever those point at in the request has
    // to come along so the Bundle stays self-contained.
    while (!toFollow.isEmpty()) {
      Bundle.BundleEntryComponent echoed = toFollow.poll();
      for (Reference ref : referencesOf(echoed.getResource())) {
        Bundle.BundleEntryComponent source =
            findEntry(originalRequest.getEntry(), ref.getReference(), baseOf(echoed));
        if (source != null && echoedSources.add(source)) {
          toFollow.add(echo(view, source));
        }
      }
    }

    echoCareTeam(view, originalRequest, echoedSources);

    // The server writes references to its own resources in relative form. Readers that resolve
    // relative references against the Bundle's last entry need that entry to be one of ours
    // whenever the ClaimResponse points at one.
    List<Bundle.BundleEntryComponent> payerSupplements = payerEntries.subList(1, payerEntries.size());
    view.getEntry().removeAll(payerSupplements);
    view.getEntry().addAll(payerSupplements);
    return view;
  }

  /**
   * The authorization is for the providers on the Claim's care team, so they are returned with
   * it: each care-team PractitionerRole and the Practitioner it names.
   */
  private static void echoCareTeam(Bundle view, Bundle originalRequest,
      Set<Bundle.BundleEntryComponent> echoedSources) {
    if (!(originalRequest.getEntryFirstRep().getResource() instanceof Claim claim)) {
      return;
    }
    for (Claim.CareTeamComponent member : claim.getCareTeam()) {
      Bundle.BundleEntryComponent provider =
          findEntry(originalRequest.getEntry(), member.getProvider().getReference(), null);
      if (provider == null) {
        continue;
      }
      if (echoedSources.add(provider)) {
        echo(view, provider);
      }
      if (provider.getResource() instanceof PractitionerRole role) {
        Bundle.BundleEntryComponent practitioner = findEntry(originalRequest.getEntry(),
            role.getPractitioner().getReference(), baseOf(provider));
        if (practitioner != null && echoedSources.add(practitioner)) {
          echo(view, practitioner);
        }
      }
    }
  }

  private static boolean isPayerAuthored(Resource resource) {
    return resource instanceof ClaimResponse || resource instanceof CommunicationRequest
        || resource instanceof Task;
  }

  /** ClaimResponse.request points at the payer's stored Claim, which is never in the Bundle. */
  private static boolean isClaimResponseRequest(Resource resource, Reference ref) {
    return resource instanceof ClaimResponse claimResponse
        && claimResponse.hasRequest() && claimResponse.getRequest() == ref;
  }

  private static List<Reference> referencesOf(Resource resource) {
    List<Reference> references = new ArrayList<>();
    for (Reference ref : R4_CTX.newTerser().getAllPopulatedChildElementsOfType(resource, Reference.class)) {
      if (ref.hasReference() && !ref.getReference().startsWith("#")) {
        references.add(ref);
      }
    }
    return references;
  }

  private static Bundle.BundleEntryComponent echo(Bundle view, Bundle.BundleEntryComponent source) {
    Bundle.BundleEntryComponent echoed = view.addEntry().setResource(source.getResource().copy());
    if (source.hasFullUrl()) {
      echoed.setFullUrl(source.getFullUrl());
    }
    return echoed;
  }

  /** The server base an entry's relative references resolve against, or null if it has none. */
  private static String baseOf(Bundle.BundleEntryComponent entry) {
    String fullUrl = entry.getFullUrl();
    Resource resource = entry.getResource();
    if (fullUrl == null || resource == null || !resource.getIdElement().hasIdPart()) {
      return null;
    }
    String suffix = "/" + resource.fhirType() + "/" + resource.getIdElement().getIdPart();
    return fullUrl.endsWith(suffix) ? fullUrl.substring(0, fullUrl.length() - suffix.length()) : null;
  }

  private static Bundle.BundleEntryComponent findEntry(List<Bundle.BundleEntryComponent> entries,
      String reference, String base) {
    if (reference == null || reference.isBlank()) {
      return null;
    }
    for (Bundle.BundleEntryComponent entry : entries) {
      if (reference.equals(entry.getFullUrl())) {
        return entry;
      }
    }
    if (reference.contains(":")) {
      // Absolute URL or urn: only an exact fullUrl match counts.
      return null;
    }
    if (base != null) {
      String absolute = base + "/" + reference;
      for (Bundle.BundleEntryComponent entry : entries) {
        if (absolute.equals(entry.getFullUrl())) {
          return entry;
        }
      }
    }
    for (Bundle.BundleEntryComponent entry : entries) {
      Resource resource = entry.getResource();
      if (resource != null && resource.getIdElement().hasIdPart()
          && reference.equals(resource.fhirType() + "/" + resource.getIdElement().getIdPart())) {
        return entry;
      }
    }
    return null;
  }
}
