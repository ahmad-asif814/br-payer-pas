package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;

class PasNotificationInterceptorTest {

  private static final String BASE = "http://payer.test/fhir";
  private static final FhirContext CTX = FhirContext.forR4Cached();

  private final PasNotificationInterceptor interceptor = new PasNotificationInterceptor(CTX, appProperties());

  @Test
  void eventNotification_isCompletedToThePasExampleShape() {
    Bundle notification = hapiEventNotification("event-notification");
    Date now = new Date();

    assertTrue(interceptor.complete(notification, "1803", now));

    Bundle.BundleEntryComponent statusEntry = notification.getEntry().get(0);
    assertEquals(BASE + "/Parameters/status-1", statusEntry.getFullUrl());
    assertEquals(Bundle.HTTPVerb.GET, statusEntry.getRequest().getMethod());
    assertEquals(BASE + "/Subscription/1803/$status", statusEntry.getRequest().getUrl());
    assertEquals("200", statusEntry.getResponse().getStatus());

    Bundle.BundleEntryComponent contentEntry = notification.getEntry().get(1);
    assertTrue(contentEntry.getFullUrl().startsWith(BASE + "/Bundle/"), "the response Bundle gets a payer URL");
    assertEquals(Bundle.HTTPVerb.GET, contentEntry.getRequest().getMethod());
    assertEquals(contentEntry.getFullUrl(), contentEntry.getRequest().getUrl());
    assertEquals("200", contentEntry.getResponse().getStatus());

    Parameters.ParametersParameterComponent event = ((Parameters) statusEntry.getResource())
        .getParameter("notification-event");
    Reference focus = (Reference) event.getPart().stream()
        .filter(p -> "focus".equals(p.getName())).findFirst().orElseThrow().getValue();
    assertEquals(contentEntry.getFullUrl(), focus.getReference(), "the event's focus is the response Bundle");
    Object timestamp = event.getPart().stream()
        .filter(p -> "timestamp".equals(p.getName())).findFirst().orElseThrow().getValue();
    assertInstanceOf(InstantType.class, timestamp);
    assertEquals(((Bundle) contentEntry.getResource()).getTimestamp(), ((InstantType) timestamp).getValue(),
        "the event time is when the response Bundle was built");
  }

  @Test
  void completedNotification_keepsItsFullUrlsThroughSerialization() {
    Bundle notification = hapiEventNotification("event-notification");
    interceptor.complete(notification, "1803", new Date());

    // HAPI re-reads the payload after the hook and sets each fullUrl from the resource id
    Bundle reread = (Bundle) CTX.newJsonParser().parseResource(CTX.newJsonParser().encodeResourceToString(notification));
    for (Bundle.BundleEntryComponent entry : reread.getEntry()) {
      assertEquals(entry.getFullUrl(), entry.getResource().getIdElement().getValue());
      assertTrue(entry.hasRequest() && entry.hasResponse());
    }
  }

  @Test
  void handshake_isLeftAsHapiBuiltIt() {
    Bundle handshake = hapiEventNotification("handshake");
    String before = CTX.newJsonParser().encodeResourceToString(handshake);

    assertFalse(interceptor.complete(handshake, "1803", new Date()));
    assertEquals(before, CTX.newJsonParser().encodeResourceToString(handshake));
  }

  @Test
  void existingFocusAndInstant_areKept() {
    Bundle notification = hapiEventNotification("event-notification");
    Parameters.ParametersParameterComponent event = ((Parameters) notification.getEntryFirstRep().getResource())
        .getParameter("notification-event");
    event.getPart().forEach(p -> {
      if ("focus".equals(p.getName())) p.setValue(new Reference("http://elsewhere.test/Bundle/1"));
      if ("timestamp".equals(p.getName())) p.setValue(new InstantType("2026-10-16T09:52:00Z"));
    });

    interceptor.complete(notification, "1803", new Date());

    assertEquals("http://elsewhere.test/Bundle/1", ((Reference) event.getPart().stream()
        .filter(p -> "focus".equals(p.getName())).findFirst().orElseThrow().getValue()).getReference());
    assertEquals("2026-10-16T09:52:00Z", event.getPart().stream()
        .filter(p -> "timestamp".equals(p.getName())).findFirst().orElseThrow().getValue().primitiveValue());
  }

  /** What HAPI delivered to Inferno: an empty focus, a date timestamp and no entry request/response. */
  private static Bundle hapiEventNotification(String type) {
    Parameters status = new Parameters();
    status.setId("status-1");
    status.addParameter().setName("subscription").setValue(new Reference("Subscription/1803"));
    status.addParameter().setName("topic").setValue(new org.hl7.fhir.r4.model.CanonicalType(PasConstants.PAS_SUBSCRIPTION_TOPIC));
    status.addParameter().setName("status").setValue(new CodeType("active"));
    status.addParameter().setName("type").setValue(new CodeType(type));
    status.addParameter().setName("events-since-subscription-start").setValue(new StringType("1"));
    Parameters.ParametersParameterComponent event = status.addParameter().setName("notification-event");
    event.addPart().setName("event-number").setValue(new StringType("1"));
    event.addPart().setName("timestamp").setValue(new DateType("2026-10-16"));
    event.addPart().setName("focus");

    Bundle content = new Bundle();
    content.setType(Bundle.BundleType.COLLECTION);
    content.setTimestamp(new Date(1_760_000_000_000L));
    content.addEntry().setResource(new ClaimResponse());

    Bundle notification = new Bundle();
    notification.setType(Bundle.BundleType.HISTORY);
    notification.addEntry().setFullUrl("Parameters/status-1").setResource(status);
    notification.addEntry().setResource(content).getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Bundle");
    return notification;
  }

  private static AppProperties appProperties() {
    AppProperties props = new AppProperties();
    props.setServer_address(BASE);
    return props;
  }
}
