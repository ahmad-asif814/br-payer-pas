package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A4;
import static org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A6;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.SimpleQuantity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Decisions that no coverage rule produces yet, switched on by configured procedure codes so the
 * full range of PAS responses can be exercised (for example by conformance test suites):
 * <ul>
 *   <li>{@code modified} - certified with changes (A6): half the requested units, returned as an
 *       addItem;</li>
 *   <li>{@code document} - pended (A4) for a clinical document requested by LOINC code;</li>
 *   <li>{@code error} - the request is rejected with ClaimResponse.error instead of a decision.</li>
 * </ul>
 * Codes are matched against Claim.item.productOrService. Codes not listed keep the regular
 * CRD-driven evaluation.
 */
@Component
@EnableConfigurationProperties(PasDemoOutcomes.Properties.class)
public class PasDemoOutcomes {

  /** Plan of care note: the document requested for the {@code document} outcome. */
  static final String REQUESTED_DOCUMENT_CODE = "18776-5";

  enum Outcome { MODIFIED, DOCUMENT, ERROR }

  @ConfigurationProperties(prefix = "pas.demo")
  public record Properties(Map<String, String> outcomes) {
    public Properties {
      if (outcomes == null) outcomes = Map.of();
    }
  }

  private final Map<String, String> outcomesByCode;

  public PasDemoOutcomes(Properties properties) {
    this.outcomesByCode = properties.outcomes();
  }

  /** True when the item's code is configured to be rejected with an error response. */
  public boolean isRejected(Claim.ItemComponent item) {
    return outcomeFor(item) == Outcome.ERROR;
  }

  /**
   * The configured decision for the item, or null when its code has no demo outcome (or is
   * rejected, which is not an item decision).
   */
  public CoverageDecision decisionFor(Claim.ItemComponent item) {
    Outcome outcome = outcomeFor(item);
    if (outcome == Outcome.MODIFIED) {
      return new CoverageDecision(REVIEW_CODE_A6, "Modified", false, null, halfOf(item));
    }
    if (outcome == Outcome.DOCUMENT) {
      return new CoverageDecision(REVIEW_CODE_A4, "Pending", true, null, null, "clinical",
          List.of(), List.of(REQUESTED_DOCUMENT_CODE));
    }
    return null;
  }

  private Outcome outcomeFor(Claim.ItemComponent item) {
    for (Coding coding : item.getProductOrService().getCoding()) {
      String outcome = coding.hasCode() ? outcomesByCode.get(coding.getCode()) : null;
      if (outcome != null) {
        try {
          return Outcome.valueOf(outcome.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          throw new IllegalStateException("Unknown pas.demo.outcomes value '" + outcome
              + "' for code " + coding.getCode() + "; expected modified, document or error", e);
        }
      }
    }
    return null;
  }

  /** Half of the requested units, rounded up, never less than one. */
  private static SimpleQuantity halfOf(Claim.ItemComponent item) {
    SimpleQuantity approved = new SimpleQuantity();
    if (item.hasQuantity()) {
      item.getQuantity().copyValues(approved);
    }
    BigDecimal requested = approved.hasValue() ? approved.getValue() : BigDecimal.ONE;
    approved.setValue(requested.divide(BigDecimal.valueOf(2), 0, RoundingMode.CEILING).max(BigDecimal.ONE));
    return approved;
  }
}
