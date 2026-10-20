package org.hl7.davinci.pas;

import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.UrlType;

/**
 * PAS Task asking the provider for a document by LOINC attachment code, alongside the
 * CommunicationRequest for the same document. Its QuestionnaireContext carries the trace number,
 * the DTR context id for the line, as for questionnaire requests. The insurer requests it, the provider owns it, and
 * the answer goes back to the payer URL through CDex. Task.reasonReference (the stored Claim) is
 * set when the Task is persisted.
 *
 * @see <a href="https://hl7.org/fhir/us/davinci-pas/2.2.1/en/StructureDefinition-profile-task.html">
 *   PAS Task</a>
 */
public final class PasTaskBuilder {

  static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";
  private static final int RESPONSE_DAYS = 14;

  private PasTaskBuilder() {
  }

  /**
   * @param insurerNpi NPI of the insurer asking, or null to fall back to its first identifier
   * @param providerNpi NPI of the provider asked, or null to fall back to its first identifier
   */
  public static Task buildAttachmentRequest(int lineNumber, String attachmentCode, Claim claim,
      String insurerNpi, String providerNpi, String payerUrl) {
    Task task = new Task();
    task.setId("task-" + UUID.randomUUID().toString().substring(0, 8));
    task.setMeta(new Meta().addProfile(PasConstants.PROFILE_PAS_TASK));
    task.addIdentifier(new Identifier()
        .setSystem(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM)
        .setValue(UUID.randomUUID().toString()));
    task.setStatus(Task.TaskStatus.REQUESTED);
    task.setStatusReason(new CodeableConcept().setText(
        "Additional documentation is needed to complete the prior authorization review"));
    task.setIntent(Task.TaskIntent.ORDER);
    task.setCode(tempCode(PasConstants.TASK_CODE_ATTACHMENT_REQUEST));
    if (claim.getPatient().hasReference()) {
      task.setFor(claim.getPatient().copy());
    }
    task.setRequester(party(claim.getInsurer(), insurerNpi));
    task.setOwner(party(claim.getProvider(), providerNpi));
    task.setReasonCode(tempCode(PasConstants.TASK_REASON_PRIOR_AUTHORIZATION));

    Date now = new Date();
    Calendar due = Calendar.getInstance();
    due.setTime(now);
    due.add(Calendar.DAY_OF_MONTH, RESPONSE_DAYS);
    task.getRestriction().setPeriod(new Period().setStart(now).setEnd(due.getTime()));

    task.addInput()
        .setType(tempCode(PasConstants.TASK_INPUT_PAYER_URL))
        .setValue(new UrlType(payerUrl));
    Task.ParameterComponent attachment = task.addInput()
        .setType(tempCode(PasConstants.TASK_INPUT_ATTACHMENTS_NEEDED))
        .setValue(new CodeableConcept().addCoding(
            new Coding(PasConstants.LOINC_SYSTEM, attachmentCode, null)));
    attachment.addExtension(PasConstants.EXT_SERVICE_LINE_NUMBER, new PositiveIntType(lineNumber));
    attachment.addExtension(PasCommunicationRequestBuilder.contentModifierExtension());

    // The DTR context for the line, so the provider can return the documentation through DTR
    // as well as attach it directly
    task.addInput()
        .setType(tempCode(PasConstants.TASK_INPUT_QUESTIONNAIRE_CONTEXT))
        .addExtension(PasConstants.EXT_SERVICE_LINE_NUMBER, new PositiveIntType(lineNumber));
    setTraceNumber(task, task.getIdentifierFirstRep());
    return task;
  }

  /**
   * Gives the Task its trace number: the identifier it shares with the matching
   * CommunicationRequest, which is also the DTR context id carried in its QuestionnaireContext.
   */
  static void setTraceNumber(Task task, Identifier traceNumber) {
    Identifier trace = traceNumber.copy();
    task.getIdentifier().clear();
    task.addIdentifier(trace);
    for (Task.ParameterComponent input : task.getInput()) {
      if (input.getType().hasCoding(PasConstants.PAS_TEMP_CODES_SYSTEM,
          PasConstants.TASK_INPUT_QUESTIONNAIRE_CONTEXT)) {
        input.setValue(new StringType(trace.getValue()));
      }
    }
  }

  /**
   * Same key the submit service gives the matching attachment-code CommunicationRequest:
   * {@code code|<service line>|<attachment code>}, or null if the Task asks for no document.
   */
  static String documentKey(Task task) {
    for (Task.ParameterComponent input : task.getInput()) {
      if (!input.getType().hasCoding(PasConstants.PAS_TEMP_CODES_SYSTEM,
          PasConstants.TASK_INPUT_ATTACHMENTS_NEEDED)
          || !(input.getValue() instanceof CodeableConcept value)) {
        continue;
      }
      Extension line = input.getExtensionByUrl(PasConstants.EXT_SERVICE_LINE_NUMBER);
      String lineValue = line != null && line.getValue() instanceof PositiveIntType p
          ? String.valueOf(p.getValue())
          : "";
      return "code|" + lineValue + "|" + value.getCodingFirstRep().getCode();
    }
    return null;
  }

  private static CodeableConcept tempCode(String code) {
    return new CodeableConcept().addCoding(new Coding(PasConstants.PAS_TEMP_CODES_SYSTEM, code, null));
  }

  /** Task.requester and Task.owner name an organization by reference and by (required) identifier. */
  private static Reference party(Reference organization, String npi) {
    Reference party = organization.copy();
    party.setIdentifier(npi != null
        ? new Identifier().setSystem(NPI_SYSTEM).setValue(npi)
        : organization.getIdentifier().copy());
    return party;
  }
}
