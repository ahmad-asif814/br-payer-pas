package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

class PasQuestionnaireAddressesTest {

  private static final String BASE = "http://payer.test/fhir";
  private static final String CANONICAL = "http://example.org/fhir/Questionnaire/DocumentationRequired";

  private PasQuestionnaireAddresses addresses;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    Questionnaire questionnaire = new Questionnaire();
    questionnaire.setId("Questionnaire/DocumentationRequired");
    questionnaire.setUrl(CANONICAL);

    IFhirResourceDao<Questionnaire> dao = mock(IFhirResourceDao.class);
    when(dao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenAnswer(inv -> inv.getArgument(0).toString().contains(CANONICAL) ? List.of(questionnaire) : List.of());
    when(dao.read(any(IdType.class), any(RequestDetails.class))).thenAnswer(inv -> {
      if ("DocumentationRequired".equals(((IdType) inv.getArgument(0)).getIdPart())) {
        return questionnaire;
      }
      throw new ResourceNotFoundException("gone");
    });
    DaoRegistry daoRegistry = mock(DaoRegistry.class);
    when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(dao);

    AppProperties props = new AppProperties();
    props.setServer_address(BASE);
    addresses = new PasQuestionnaireAddresses(props, daoRegistry);
  }

  @Test
  void presentToProvider_namesTheQuestionnaireByItsPayerAddress() {
    CommunicationRequest request = new CommunicationRequest();
    request.addExtension(PasConstants.EXT_REQUESTED_QUESTIONNAIRE, new CanonicalType(CANONICAL));

    addresses.presentToProvider(request);

    Extension ext = request.getExtension().get(0);
    assertEquals(BASE + "/StructureDefinition/extension-requestedQuestionnaire", ext.getUrl());
    assertTrue(ext.getUrl().endsWith("extension-requestedQuestionnaire"), "providers match on the last segment");
    assertEquals(BASE + "/Questionnaire/DocumentationRequired", ((CanonicalType) ext.getValue()).getValue());
  }

  @Test
  void presentToProvider_keepsACanonicalThatIsNotOnThisServer() {
    CommunicationRequest request = new CommunicationRequest();
    request.addExtension(PasConstants.EXT_REQUESTED_QUESTIONNAIRE,
        new CanonicalType("http://elsewhere.test/Questionnaire/X"));

    addresses.presentToProvider(request);

    assertEquals("http://elsewhere.test/Questionnaire/X",
        ((CanonicalType) request.getExtension().get(0).getValue()).getValue());
  }

  @Test
  void canonicalOf_turnsThePayerAddressBackIntoTheCanonical() {
    assertEquals(CANONICAL, addresses.canonicalOf(BASE + "/Questionnaire/DocumentationRequired"));
    assertEquals(CANONICAL, addresses.canonicalOf(BASE + "/Questionnaire/DocumentationRequired|1.0.0"));
    assertEquals(CANONICAL, addresses.canonicalOf(CANONICAL), "a canonical is returned as is");
    assertEquals(BASE + "/Questionnaire/Missing", addresses.canonicalOf(BASE + "/Questionnaire/Missing"));
    assertNull(addresses.canonicalOf(null));
  }
}
