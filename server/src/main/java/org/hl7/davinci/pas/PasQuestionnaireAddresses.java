package org.hl7.davinci.pas;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * How the questionnaire behind a documentation request is named to providers.
 *
 * Internally a questionnaire request records the Questionnaire's canonical in a payer extension.
 * The content library publishes most canonicals under example.org, which conformance validators
 * reject in a response, so providers are given the payer's own names instead: the extension under
 * this server's base, and the questionnaire as its address on this server. Documentation that
 * names that address is matched to the canonical it stands for.
 */
@Component
public class PasQuestionnaireAddresses {

  private final String serverBase;
  private final DaoRegistry daoRegistry;

  public PasQuestionnaireAddresses(AppProperties appProperties, DaoRegistry daoRegistry) {
    this.serverBase = FhirUtil.normalizeServerBase(appProperties.getServer_address());
    this.daoRegistry = daoRegistry;
  }

  /** The requested-questionnaire extension URL providers see. Its last segment stays the same. */
  public String providerExtensionUrl() {
    return serverBase + "/StructureDefinition/extension-requestedQuestionnaire";
  }

  /** This server's address for the questionnaire a canonical names, or null if it has none. */
  public String providerAddress(String canonical) {
    Questionnaire questionnaire = FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonical);
    return questionnaire != null && serverBase != null
        ? serverBase + "/Questionnaire/" + questionnaire.getIdElement().getIdPart()
        : null;
  }

  /**
   * The canonical a questionnaire reference stands for: a reference to a Questionnaire on this
   * server becomes that Questionnaire's canonical; anything else is returned unchanged.
   */
  public String canonicalOf(String reference) {
    String prefix = serverBase != null ? serverBase + "/Questionnaire/" : null;
    if (reference == null || prefix == null || !reference.startsWith(prefix)) {
      return reference;
    }
    String id = reference.substring(prefix.length());
    int end = id.indexOf('|');
    if (end >= 0) {
      id = id.substring(0, end);
    }
    if (id.isBlank() || id.contains("/")) {
      return reference;
    }
    try {
      Questionnaire questionnaire = daoRegistry.getResourceDao(Questionnaire.class)
          .read(new IdType("Questionnaire", id), new SystemRequestDetails());
      return questionnaire.hasUrl() ? questionnaire.getUrl() : reference;
    } catch (RuntimeException e) {
      return reference;
    }
  }

  /**
   * Rewrites a CommunicationRequest that is about to leave the payer so its requested
   * questionnaire uses the provider-facing extension URL and address. The canonical is kept when
   * the questionnaire is not on this server.
   */
  public void presentToProvider(CommunicationRequest request) {
    Extension ext = request.getExtensionByUrl(PasConstants.EXT_REQUESTED_QUESTIONNAIRE);
    if (ext == null) {
      return;
    }
    ext.setUrl(providerExtensionUrl());
    if (ext.getValue() instanceof CanonicalType canonical) {
      String address = providerAddress(canonical.getValue());
      if (address != null) {
        ext.setValue(new CanonicalType(address));
      }
    }
  }
}
