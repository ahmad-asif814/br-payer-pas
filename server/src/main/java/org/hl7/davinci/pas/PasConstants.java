package org.hl7.davinci.pas;

/**
 * PAS IG extension URLs, review action codes, and profile URL constants.
 */
public final class PasConstants {

  private PasConstants() {
  }

  // Base URL for PAS extensions
  public static final String PAS_BASE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/";

  // ClaimResponse extensions
  public static final String REVIEW_ACTION = PAS_BASE + "extension-reviewAction";
  public static final String REVIEW_ACTION_CODE = PAS_BASE + "extension-reviewActionCode";
  public static final String AUTHORIZATION_NUMBER = PAS_BASE + "extension-authorizationNumber";
  public static final String ADMIN_REF_NUMBER = PAS_BASE + "extension-administrationReferenceNumber";

  // ClaimResponse.item extensions
  public static final String ITEM_REQUESTED_SERVICE_DATE = PAS_BASE + "extension-itemRequestedServiceDate";
  public static final String ITEM_PREAUTH_ISSUE_DATE = PAS_BASE + "extension-itemPreAuthIssueDate";
  public static final String ITEM_PREAUTH_PERIOD = PAS_BASE + "extension-itemPreAuthPeriod";
  public static final String ITEM_AUTHORIZED_PROVIDER = PAS_BASE + "extension-itemAuthorizedProvider";
  public static final String ITEM_AUTHORIZED_DETAIL = PAS_BASE + "extension-itemAuthorizedDetail";
  public static final String ITEM_TRACE_NUMBER = PAS_BASE + "extension-itemTraceNumber";
  public static final String ADMISSION_DATES = PAS_BASE + "extension-admissionDates";
  public static final String DISCHARGE_DATE = PAS_BASE + "extension-dischargeDate";

  // ClaimResponse header, addItem and error extensions
  public static final String CLAIM_RESPONSE_REVIEWER = PAS_BASE + "extension-claimResponseReviewer";
  public static final String AUTHORIZED_PROVIDER_TYPE = PAS_BASE + "extension-authorizedProviderType";
  public static final String REVENUE_CODE = PAS_BASE + "extension-revenueCode";
  public static final String ERROR_FOLLOWUP_ACTION = PAS_BASE + "extension-errorFollowupAction";
  public static final String ERROR_ELEMENT = PAS_BASE + "extension-errorElement";
  public static final String ERROR_PATH = PAS_BASE + "extension-errorPath";
  public static final String IDENTIFIER_SUB_DEPARTMENT = PAS_BASE + "extension-identifierSubDepartment";
  public static final String IDENTIFIER_JURISDICTION = PAS_BASE + "extension-identifierJurisdiction";
  public static final String DATA_ABSENT_REASON = "http://hl7.org/fhir/StructureDefinition/data-absent-reason";

  // Claim.item extensions a modified (addItem) line carries over from the requested line
  public static final String EPSDT_INDICATOR = PAS_BASE + "extension-epsdtIndicator";
  public static final String NURSING_HOME_RESIDENTIAL_STATUS = PAS_BASE + "extension-nursingHomeResidentialStatus";
  public static final String NURSING_HOME_LEVEL_OF_CARE = PAS_BASE + "extension-nursingHomeLevelOfCare";
  public static final String PRODUCT_OR_SERVICE_CODE_END = PAS_BASE + "extension-productOrServiceCodeEnd";
  public static final String REVENUE_UNIT_RATE_LIMIT = PAS_BASE + "extension-revenueUnitRateLimit";

  // Claim extensions
  public static final String CARE_TEAM_CLAIM_SCOPE = PAS_BASE + "extension-careTeamClaimScope";
  public static final String CERTIFICATION_TYPE = PAS_BASE + "extension-certificationType";
  public static final String SERVICE_ITEM_REQUEST_TYPE = PAS_BASE + "extension-serviceItemRequestType";
  public static final String LEVEL_OF_SERVICE_CODE = PAS_BASE + "extension-levelOfServiceCode";
  public static final String TRANSMISSION_IDENTIFIERS = PAS_BASE + "extension-TransmissionIdentifiers";
  public static final String ITEM_REQUESTED_SERVICE = PAS_BASE + "extension-requestedService";

  // Claim Update extensions
  public static final String INFO_CHANGED = PAS_BASE + "extension-infoChanged";
  public static final String INFO_CANCELLED = PAS_BASE + "modifierextension-infoCancelled";

  // Subscription Topic
  public static final String PAS_SUBSCRIPTION_TOPIC =
      "http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic";
  public static final String PROFILE_PAS_SUBSCRIPTION =
      "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-subscription";

  // Subscription Backport extensions
  public static final String BACKPORT_FILTER_CRITERIA =
      "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-filter-criteria";
  public static final String BACKPORT_PAYLOAD_CONTENT =
      "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-payload-content";

  // HAPI topic subscription meta tag
  public static final String SUBSCRIPTION_MATCHING_TAG_SYSTEM =
      "http://hapifhir.io/fhir/StructureDefinition/subscription-matching-strategy";
  public static final String SUBSCRIPTION_MATCHING_TAG_CODE = "TOPIC";

  // Filter parameter
  public static final String FILTER_ORG_IDENTIFIER = "org-identifier";

  // PAS Profile URLs
  public static final String PROFILE_PAS_REQUEST_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-request-bundle";
  public static final String PROFILE_PAS_INQUIRY_REQUEST_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-inquiry-request-bundle";
  public static final String PROFILE_PAS_RESPONSE_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-response-bundle";
  public static final String PROFILE_PAS_INQUIRY_RESPONSE_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-inquiry-response-bundle";
  public static final String PROFILE_PAS_CLAIM = PAS_BASE + "profile-claim";
  public static final String PROFILE_PAS_CLAIM_INQUIRY = PAS_BASE + "profile-claim-inquiry";
  public static final String PROFILE_PAS_CLAIM_RESPONSE = PAS_BASE + "profile-claimresponse";
  public static final String PROFILE_PAS_CLAIM_UPDATE = PAS_BASE + "profile-claim-update";
  public static final String PROFILE_PAS_BENEFICIARY = PAS_BASE + "profile-beneficiary";
  public static final String PROFILE_PAS_SUBSCRIBER = PAS_BASE + "profile-subscriber";
  public static final String PROFILE_PAS_INSURER = PAS_BASE + "profile-insurer";
  public static final String PROFILE_PAS_REQUESTOR = PAS_BASE + "profile-requestor";
  public static final String PROFILE_PAS_COVERAGE = PAS_BASE + "profile-coverage";
  public static final String PROFILE_PAS_PRACTITIONER_ROLE = PAS_BASE + "profile-practitionerrole";
  public static final String PROFILE_PAS_PRACTITIONER = PAS_BASE + "profile-practitioner";
  public static final String PROFILE_PAS_SERVICE_REQUEST = PAS_BASE + "profile-servicerequest";
  public static final String PROFILE_PAS_DEVICE_REQUEST = PAS_BASE + "profile-devicerequest";
  public static final String PROFILE_PAS_MEDICATION_REQUEST = PAS_BASE + "profile-medicationrequest";
  public static final String PROFILE_PAS_COMMUNICATION_REQUEST = PAS_BASE + "profile-communicationrequest";
  public static final String PROFILE_PAS_TASK = PAS_BASE + "profile-task";

  public static final String EXT_SERVICE_LINE_NUMBER = PAS_BASE + "extension-serviceLineNumber";
  public static final String EXT_CONTENT_MODIFIER = PAS_BASE + "extension-contentModifier";
  public static final String EXT_COMMUNICATED_DIAGNOSIS = PAS_BASE + "extension-communicatedDiagnosis";

  public static final String X12_REQUEST_CATEGORY_SYSTEM = "https://codesystem.x12.org/005010/755";

  // PAS temporary codes; CDEX marks additional information as returned through CDex $submit-attachment
  public static final String PAS_TEMP_CODES_SYSTEM = "http://hl7.org/fhir/us/davinci-pas/CodeSystem/PASTempCodes";
  public static final String MEDIUM_CDEX = "CDEX";
  public static final String TASK_CODE_ATTACHMENT_REQUEST = "attachment-request-code";
  public static final String TASK_REASON_PRIOR_AUTHORIZATION = "priorAuthorization";
  public static final String TASK_INPUT_PAYER_URL = "payer-url";
  public static final String TASK_INPUT_ATTACHMENTS_NEEDED = "attachments-needed";
  public static final String TASK_INPUT_QUESTIONNAIRE_CONTEXT = "questionnaire-context";

  // Additional-information request codes (CommunicationRequest.payload.content required binding)
  public static final String LOINC_SYSTEM = "http://loinc.org";
  public static final String LOINC_QUESTIONNAIRE_REQUEST = "102089-0";
  // Attachment modifier sent with every document-by-code request, as in the PAS
  // CommunicationRequest example (X12278DiagnosisInformationType)
  public static final String LOINC_ATTACHMENT_MODIFIER = "18804-5";

  // X12 codes for rejected requests: 901 reject reason, 889 follow-up action, 98 entity type
  public static final String X12_REJECT_REASON_SYSTEM = "https://codesystem.x12.org/005010/901";
  public static final String X12_FOLLOWUP_ACTION_SYSTEM = "https://codesystem.x12.org/005010/889";
  public static final String X12_ENTITY_IDENTIFIER_SYSTEM = "https://codesystem.x12.org/005010/98";

  // Item trace number namespace for payer-assigned documentation request traces. PAS
  // PASIdentifier recommends a scheme of urn:trnorg:<TRN03>; the value is a unique trace number
  // minted per documentation request (X12 TRN02 semantics), also usable as the DTR context id.
  public static final String QUESTIONNAIRE_TRACE_NUMBER_SYSTEM = "urn:trnorg:PASPAYER";

  // Payer-internal extension recording which Questionnaire a 102089-0 CommunicationRequest asks
  // for; the wire association for providers is the opaque trace number resolved through DTR.
  public static final String EXT_REQUESTED_QUESTIONNAIRE =
      "http://example.org/fhir/us/davinci-pas/StructureDefinition/extension-requestedQuestionnaire";

  // Internal meta tag marking a ClaimResponse as awaiting pended resolution
  public static final String PENDED_TAG_SYSTEM = "http://example.org/fhir/us/davinci-pas/internal-tags";
  public static final String PENDED_TAG_CODE = "pended-resolution";

}
