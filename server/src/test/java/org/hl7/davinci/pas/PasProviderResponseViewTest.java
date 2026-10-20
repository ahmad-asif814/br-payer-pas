package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;

class PasProviderResponseViewTest {

  private static final String PAYER_BASE = "http://payer.test/fhir";
  private static final String PROVIDER_BASE = "http://provider.test/fhir";

  @Test
  void forSubmit_echoesProviderResourcesWithTheirOwnFullUrlAndId() {
    Bundle view = PasProviderResponseView.forSubmit(storedResponse(), providerRequest(), refMap());

    ClaimResponse cr = (ClaimResponse) view.getEntryFirstRep().getResource();
    assertEquals(PROVIDER_BASE + "/Patient/pat-1", cr.getPatient().getReference());
    assertEquals(PROVIDER_BASE + "/Organization/insurer-1", cr.getInsurer().getReference());
    assertEquals(PROVIDER_BASE + "/Organization/provider-1", cr.getRequestor().getReference());

    Bundle.BundleEntryComponent patientEntry = entryOf(view, Patient.class);
    assertEquals(PROVIDER_BASE + "/Patient/pat-1", patientEntry.getFullUrl());
    assertEquals("pat-1", patientEntry.getResource().getIdElement().getIdPart());
    assertEquals("MEMBER-1", ((Patient) patientEntry.getResource()).getIdentifierFirstRep().getValue());
    assertEquals(2, view.getEntry().stream().filter(e -> e.getResource() instanceof Organization).count());
  }

  @Test
  void forSubmit_dropsPayerSideCopiesOfProviderResources() {
    Bundle view = PasProviderResponseView.forSubmit(storedResponse(), providerRequest(), refMap());

    assertTrue(view.getEntry().stream().noneMatch(e -> e.getFullUrl().startsWith(PAYER_BASE + "/Patient")),
        "The payer's stored copy of the Patient must not be returned");
  }

  @Test
  void forSubmit_keepsRequestReferenceAndLeavesStoredResponseUntouched() {
    Bundle stored = storedResponse();

    Bundle view = PasProviderResponseView.forSubmit(stored, providerRequest(), refMap());

    ClaimResponse viewCr = (ClaimResponse) view.getEntryFirstRep().getResource();
    assertEquals("Claim/900", viewCr.getRequest().getReference());
    assertNull(viewCr.getMeta().getTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE));
    ClaimResponse storedCr = (ClaimResponse) stored.getEntryFirstRep().getResource();
    assertEquals("Patient/100", storedCr.getPatient().getReference());
    assertNotNull(storedCr.getMeta().getTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE));
  }

  @Test
  void forSubmit_resolvesCommunicationRequestReferencesInsideTheBundle() {
    Bundle view = PasProviderResponseView.forSubmit(storedResponse(), providerRequest(), refMap());

    ClaimResponse cr = (ClaimResponse) view.getEntryFirstRep().getResource();
    assertEquals(PAYER_BASE + "/CommunicationRequest/300",
        cr.getCommunicationRequestFirstRep().getReference());
    CommunicationRequest request = (CommunicationRequest) entryOf(view, CommunicationRequest.class).getResource();
    assertEquals(PROVIDER_BASE + "/Patient/pat-1", request.getSubject().getReference());
    assertEquals(PROVIDER_BASE + "/Organization/insurer-1", request.getRequester().getReference());
  }

  @Test
  void forSubmit_returnsTheClaimsCareTeam() {
    Bundle view = PasProviderResponseView.forSubmit(storedResponse(), providerRequest(), refMap());

    assertEquals(PROVIDER_BASE + "/PractitionerRole/role-1", entryOf(view, PractitionerRole.class).getFullUrl());
    assertEquals(PROVIDER_BASE + "/Practitioner/prac-1", entryOf(view, Practitioner.class).getFullUrl());
  }

  @Test
  void forSubmit_putsThePayersOwnSupplementsLast() {
    Bundle view = PasProviderResponseView.forSubmit(storedResponse(), providerRequest(), refMap());

    assertInstanceOf(ClaimResponse.class, view.getEntryFirstRep().getResource());
    Bundle.BundleEntryComponent last = view.getEntry().get(view.getEntry().size() - 1);
    assertEquals(PAYER_BASE + "/CommunicationRequest/300", last.getFullUrl());
  }

  private static Bundle.BundleEntryComponent entryOf(Bundle bundle, Class<?> type) {
    return bundle.getEntry().stream()
        .filter(e -> type.isInstance(e.getResource()))
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, String> refMap() {
    Map<String, String> refMap = new LinkedHashMap<>();
    refMap.put("Patient/pat-1", "Patient/100");
    refMap.put("Organization/insurer-1", "Organization/200");
    refMap.put("Organization/provider-1", "Organization/201");
    return refMap;
  }

  private static Bundle providerRequest() {
    Bundle request = new Bundle();
    request.setType(Bundle.BundleType.COLLECTION);
    Claim claim = new Claim();
    claim.setId("claim-1");
    claim.addCareTeam().setProvider(new Reference("PractitionerRole/role-1"));
    request.addEntry().setFullUrl(PROVIDER_BASE + "/Claim/claim-1").setResource(claim);
    Patient patient = new Patient();
    patient.setId("pat-1");
    patient.addIdentifier().setSystem("http://provider.test/member").setValue("MEMBER-1");
    request.addEntry().setFullUrl(PROVIDER_BASE + "/Patient/pat-1").setResource(patient);
    Organization insurer = new Organization();
    insurer.setId("insurer-1");
    request.addEntry().setFullUrl(PROVIDER_BASE + "/Organization/insurer-1").setResource(insurer);
    Organization provider = new Organization();
    provider.setId("provider-1");
    request.addEntry().setFullUrl(PROVIDER_BASE + "/Organization/provider-1").setResource(provider);
    PractitionerRole role = new PractitionerRole();
    role.setId("role-1");
    role.setPractitioner(new Reference("Practitioner/prac-1"));
    request.addEntry().setFullUrl(PROVIDER_BASE + "/PractitionerRole/role-1").setResource(role);
    Practitioner practitioner = new Practitioner();
    practitioner.setId("prac-1");
    request.addEntry().setFullUrl(PROVIDER_BASE + "/Practitioner/prac-1").setResource(practitioner);
    return request;
  }

  /** The response as the payer persists it: payer ids, payer copies, internal bookkeeping. */
  private static Bundle storedResponse() {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("500");
    cr.getMeta().addTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE, "Pended Resolution");
    cr.setPatient(new Reference("Patient/100"));
    cr.setInsurer(new Reference("Organization/200"));
    cr.setRequestor(new Reference("Organization/201"));
    cr.setRequest(new Reference("Claim/900"));
    cr.setCommunicationRequest(List.of(new Reference("CommunicationRequest/300")));

    CommunicationRequest request = new CommunicationRequest();
    request.setId("300");
    request.setSubject(new Reference("Patient/100"));
    request.setRequester(new Reference("Organization/200"));

    Patient payerPatient = new Patient();
    payerPatient.setId("100");

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setFullUrl(PAYER_BASE + "/ClaimResponse/500").setResource(cr);
    bundle.addEntry().setFullUrl(PAYER_BASE + "/Patient/100").setResource(payerPatient);
    bundle.addEntry().setFullUrl(PAYER_BASE + "/CommunicationRequest/300").setResource(request);
    return bundle;
  }
}
