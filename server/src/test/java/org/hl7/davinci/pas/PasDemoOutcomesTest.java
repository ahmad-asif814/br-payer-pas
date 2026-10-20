package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A4;
import static org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A6;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.SimpleQuantity;
import org.junit.jupiter.api.Test;

class PasDemoOutcomesTest {

  private final PasDemoOutcomes outcomes = new PasDemoOutcomes(
      new PasDemoOutcomes.Properties(Map.of("42", "modified", "PT", "Document", "6", "error")));

  @Test
  void modified_certifiesHalfTheRequestedUnitsRoundedUp() {
    CoverageDecision decision = outcomes.decisionFor(item("42", 5));

    assertEquals(REVIEW_CODE_A6, decision.reviewActionCode());
    assertFalse(decision.isPended());
    assertEquals(3, decision.modifiedQuantity().getValue().intValue());
    assertEquals("visit", decision.modifiedQuantity().getUnit());
  }

  @Test
  void modified_neverCertifiesLessThanOneUnit() {
    assertEquals(1, outcomes.decisionFor(item("42", 1)).modifiedQuantity().getValue().intValue());
  }

  @Test
  void document_pendsForAPlanOfCareNote() {
    CoverageDecision decision = outcomes.decisionFor(item("PT", 1));

    assertEquals(REVIEW_CODE_A4, decision.reviewActionCode());
    assertTrue(decision.isPended());
    assertEquals(List.of(PasDemoOutcomes.REQUESTED_DOCUMENT_CODE), decision.requestedAttachmentCodes());
    assertTrue(decision.hasAdditionalDocumentationInfo());
  }

  @Test
  void error_rejectsTheLineInsteadOfDecidingIt() {
    assertTrue(outcomes.isRejected(item("6", 1)));
    assertNull(outcomes.decisionFor(item("6", 1)));
  }

  @Test
  void otherCodes_keepTheRegularEvaluation() {
    assertNull(outcomes.decisionFor(item("E0466", 1)));
    assertFalse(outcomes.isRejected(item("E0466", 1)));
    assertNull(new PasDemoOutcomes(new PasDemoOutcomes.Properties(null)).decisionFor(item("42", 2)));
  }

  @Test
  void unknownOutcome_failsLoudly() {
    PasDemoOutcomes misconfigured = new PasDemoOutcomes(new PasDemoOutcomes.Properties(Map.of("42", "approve")));
    assertThrows(IllegalStateException.class, () -> misconfigured.decisionFor(item("42", 1)));
  }

  private static Claim.ItemComponent item(String code, int units) {
    Claim.ItemComponent item = new Claim.ItemComponent();
    item.setSequence(1);
    item.setProductOrService(new CodeableConcept().addCoding(
        new Coding("https://codesystem.x12.org/005010/1365", code, null)));
    SimpleQuantity quantity = new SimpleQuantity();
    quantity.setValue(units).setUnit("visit");
    item.setQuantity(quantity);
    return item;
  }
}
