package org.hl7.davinci.pas;

import java.util.Date;
import java.util.UUID;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.EncodingEnum;

/**
 * Completes PAS event notifications before they are delivered over rest-hook, to the shape of the
 * PAS IG notification example (Bundle-PASSubscriptionNotification).
 *
 * HAPI builds the notification from the dispatched PAS response Bundle, which has no identity of
 * its own, so the notification-event has no focus; HAPI also writes the event timestamp as a date
 * and leaves out the request and response that every history Bundle entry needs. This gives the
 * SubscriptionStatus and the PAS response Bundle full URLs on this server, points the event's
 * focus at the response Bundle, records the timestamp as an instant and fills in each entry's
 * request and response. Handshakes, heartbeats and other topics are delivered as HAPI built them.
 *
 * HAPI sets each delivered entry's fullUrl from its resource id after this hook runs, so the ids
 * are set to the full URLs.
 */
@Interceptor
@Component
public class PasNotificationInterceptor {

  private static final String EVENT_NOTIFICATION = "event-notification";

  private final FhirContext fhirContext;
  private final String serverBase;

  public PasNotificationInterceptor(FhirContext fhirContext, AppProperties appProperties) {
    this.fhirContext = fhirContext;
    this.serverBase = FhirUtil.normalizeServerBase(appProperties.getServer_address());
  }

  @Hook(Pointcut.SUBSCRIPTION_BEFORE_REST_HOOK_DELIVERY)
  public boolean beforeRestHookDelivery(CanonicalSubscription subscription, ResourceDeliveryMessage message) {
    if (subscription == null || !subscription.isTopicSubscription()
        || !PasConstants.PAS_SUBSCRIPTION_TOPIC.equals(subscription.getTopic())) {
      return true;
    }
    IBaseResource payload = message.getPayload(fhirContext);
    if (payload instanceof Bundle notification
        && complete(notification, subscription.getIdElement(fhirContext).getIdPart(), new Date())) {
      message.setPayload(fhirContext, notification, EncodingEnum.JSON);
    }
    return true;
  }

  /**
   * Completes a PAS event notification in place.
   *
   * @return false when the Bundle is not an event notification and was left alone
   */
  boolean complete(Bundle notification, String subscriptionId, Date now) {
    if (notification.getType() != Bundle.BundleType.HISTORY || serverBase == null) {
      return false;
    }
    Bundle.BundleEntryComponent statusEntry = notification.getEntry().stream()
        .filter(entry -> entry.getResource() instanceof Parameters)
        .findFirst()
        .orElse(null);
    if (statusEntry == null) {
      return false;
    }
    Parameters status = (Parameters) statusEntry.getResource();
    Parameters.ParametersParameterComponent type = status.getParameter("type");
    if (type == null || type.getValue() == null
        || !EVENT_NOTIFICATION.equals(type.getValue().primitiveValue())) {
      return false;
    }

    // The SubscriptionStatus answers this subscription's $status
    String statusUrl = identify(statusEntry);
    statusEntry.getRequest().setMethod(Bundle.HTTPVerb.GET)
        .setUrl(serverBase + "/Subscription/" + subscriptionId + "/$status");
    statusEntry.getResponse().setStatus("200");

    // The event content: the PAS response Bundle the payer dispatched
    String focusUrl = null;
    Date eventTime = null;
    for (Bundle.BundleEntryComponent entry : notification.getEntry()) {
      if (entry == statusEntry || entry.getResource() == null) {
        continue;
      }
      String fullUrl = identify(entry);
      entry.getRequest().setMethod(Bundle.HTTPVerb.GET).setUrl(fullUrl);
      entry.getResponse().setStatus("200");
      if (focusUrl == null) {
        focusUrl = fullUrl;
        if (entry.getResource() instanceof Bundle content && content.hasTimestamp()) {
          eventTime = content.getTimestamp();
        }
      }
    }

    for (Parameters.ParametersParameterComponent event : status.getParameter()) {
      if (!"notification-event".equals(event.getName())) {
        continue;
      }
      Parameters.ParametersParameterComponent focus = part(event, "focus");
      if (focusUrl != null && !(focus.getValue() instanceof Reference ref && ref.hasReference())) {
        focus.setValue(new Reference(focusUrl));
      }
      Parameters.ParametersParameterComponent timestamp = part(event, "timestamp");
      if (!(timestamp.getValue() instanceof InstantType)) {
        timestamp.setValue(new InstantType(eventTime != null ? eventTime : now));
      }
    }
    return true;
  }

  /** Gives the entry's resource an id on this server and returns the entry's full URL. */
  private String identify(Bundle.BundleEntryComponent entry) {
    Resource resource = entry.getResource();
    String idPart = resource.getIdElement().getIdPart();
    if (idPart == null || idPart.isBlank()) {
      idPart = UUID.randomUUID().toString();
    }
    IdType id = new IdType(serverBase, resource.fhirType(), idPart, null);
    resource.setIdElement(id);
    entry.setFullUrl(id.getValue());
    return id.getValue();
  }

  private static Parameters.ParametersParameterComponent part(
      Parameters.ParametersParameterComponent parameter, String name) {
    return parameter.getPart().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseGet(() -> parameter.addPart().setName(name));
  }
}
