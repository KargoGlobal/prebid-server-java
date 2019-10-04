package org.prebid.server.handler;

import io.netty.util.AsciiString;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.analytics.AnalyticsReporter;
import org.prebid.server.analytics.model.SetuidEvent;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.cookie.UidsCookieService;
import org.prebid.server.cookie.model.UidWithExpiry;
import org.prebid.server.cookie.proto.Uids;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.execution.TimeoutFactory;
import org.prebid.server.gdpr.GdprService;
import org.prebid.server.gdpr.model.GdprResponse;
import org.prebid.server.metric.Metrics;
import org.prebid.server.rubicon.audit.UidsAuditCookieService;
import org.prebid.server.rubicon.audit.proto.UidAudit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class SetuidHandlerTest extends VertxTest {

    private static final String RUBICON = "rubicon";
    private static final String ADNXS = "adnxs";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private UidsCookieService uidsCookieService;
    @Mock
    private UidsAuditCookieService uidsAuditCookieService;
    @Mock
    private GdprService gdprService;
    @Mock
    private AnalyticsReporter analyticsReporter;
    @Mock
    private Metrics metrics;

    private SetuidHandler setuidHandler;
    @Mock
    private RoutingContext routingContext;
    @Mock
    private HttpServerRequest httpRequest;
    @Mock
    private HttpServerResponse httpResponse;
    @Mock
    private MultiMap responseHeaders;

    @Before
    public void setUp() {
        given(gdprService.resultByVendor(anySet(), anySet(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(GdprResponse.of(true, singletonMap(null, true), null)));

        given(routingContext.request()).willReturn(httpRequest);
        given(routingContext.response()).willReturn(httpResponse);

        given(uidsCookieService.toCookie(any()))
                .willReturn(Cookie.cookie("test", "test"));

        given(httpRequest.getParam("account")).willReturn("account");
        given(httpResponse.headers()).willReturn(responseHeaders);

        final Clock clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        final TimeoutFactory timeoutFactory = new TimeoutFactory(clock);
        setuidHandler = new SetuidHandler(2000, uidsCookieService, gdprService, null, false, analyticsReporter, metrics,
                timeoutFactory, true, uidsAuditCookieService);
    }

    @Test
    public void shouldRespondWithEmptyBodyAndNoContentStatusIfCookieIsDisabled() {
        // given
        final Clock clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        final TimeoutFactory timeoutFactory = new TimeoutFactory(clock);
        setuidHandler = new SetuidHandler(2000, uidsCookieService, gdprService, null, false, analyticsReporter, metrics,
                timeoutFactory, false, uidsAuditCookieService);
        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(204));
        verify(httpResponse).end();
    }

    @Test
    public void shouldRespondWithErrorIfOptedOut() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).optout(true).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(401));
        verify(httpResponse).end();
    }

    @Test
    public void shouldRespondWithErrorIfBidderParamIsMissing() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end(eq("\"bidder\" query param is required"));
    }

    @Test
    public void shouldRespondWithErrorIfUserInGdprScopeAndAccountParamIsMissing() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));
        given(httpRequest.getParam("account")).willReturn(null);
        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end(eq("\"account\" query param is required"));
    }

    @Test
    public void shouldRespondWithoutCookieIfGdprProcessingPreventsCookieSetting() {
        // given
        given(gdprService.resultByVendor(anySet(), anySet(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(GdprResponse.of(true, singletonMap(null, false), null)));

        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(routingContext, never()).addCookie(any());
        verify(httpResponse).setStatusCode(eq(200));
        verify(httpResponse).end(eq("The gdpr_consent param prevents cookies from being saved"));
    }

    @Test
    public void shouldRespondWithBadRequestStatusIfGdprProcessingFailsWithInvalidRequestException() {
        // given
        given(gdprService.resultByVendor(anySet(), anySet(), any(), any(), any(), any(), any()))
                .willReturn(Future.failedFuture(new InvalidRequestException("gdpr exception")));

        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(routingContext, never()).addCookie(any());
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end(eq("GDPR processing failed with error: gdpr exception"));
    }

    @Test
    public void shouldRespondWithInternalServerErrorStatusIfGdprProcessingFailsWithUnexpectedException() {
        // given
        given(gdprService.resultByVendor(anySet(), anySet(), any(), any(), any(), any(), any()))
                .willReturn(Future.failedFuture("unexpected error"));

        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse, never()).sendFile(any());
        verify(routingContext, never()).addCookie(any());
        verify(httpResponse).setStatusCode(eq(500));
        verify(httpResponse).end(eq("Unexpected GDPR processing error"));
    }

    @Test
    public void shouldPassIpAddressToGdprServiceIfGeoLocationEnabled() {
        // given
        final Clock clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        final TimeoutFactory timeoutFactory = new TimeoutFactory(clock);
        setuidHandler = new SetuidHandler(2000, uidsCookieService, gdprService, null, true, analyticsReporter, metrics,
                timeoutFactory, true, uidsAuditCookieService);

        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        final MultiMap headers = mock(MultiMap.class);
        given(httpRequest.headers()).willReturn(headers);
        given(headers.get("X-Forwarded-For")).willReturn("192.168.144.1");

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(gdprService).resultByVendor(anySet(), anySet(), any(), any(), eq("192.168.144.1"), any(), any());
    }

    @Test
    public void shouldRemoveUidFromCookieIfMissingInRequest() {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"));
        uids.put(ADNXS, UidWithExpiry.live("12345"));
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("format")).willReturn("img");

        // this uids cookie stands for {"tempUIDs":{"adnxs":{"uid":"12345"}}}
        given(uidsCookieService.toCookie(any())).willReturn(Cookie
                .cookie("uids", "eyJ0ZW1wVUlEcyI6eyJhZG54cyI6eyJ1aWQiOiIxMjM0NSJ9fX0="));

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(routingContext, never()).addCookie(any());
        verify(httpResponse).sendFile(any());

        final String uidsCookie = captureCookie();
        final Uids decodedUids = decodeUids(uidsCookie);
        assertThat(decodedUids.getUids()).hasSize(1);
        assertThat(decodedUids.getUids().get(ADNXS).getUid()).isEqualTo("12345");
    }

    @Test
    public void shouldIgnoreFacebookSentinel() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap("audienceNetwork", UidWithExpiry.live("facebookUid"))).build()));

        given(httpRequest.getParam("bidder")).willReturn("audienceNetwork");
        given(httpRequest.getParam("uid")).willReturn("0");

        // this uids cookie value stands for {"tempUIDs":{"audienceNetwork":{"uid":"facebookUid"}}}
        given(uidsCookieService.toCookie(any())).willReturn(Cookie
                .cookie("uids", "eyJ0ZW1wVUlEcyI6eyJhdWRpZW5jZU5ldHdvcmsiOnsidWlkIjoiZmFjZWJvb2tVaWQifX19"));

        given(uidsAuditCookieService.getUidsAudit(any(RoutingContext.class))).willReturn(UidAudit.builder().build());
        given(uidsAuditCookieService.createUidsAuditCookie(any(), any(), any(), any(), any(), any()))
                .willReturn(Cookie.cookie("uids-audit", "value").setDomain("rubicon"));

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(routingContext, never()).addCookie(any());
        verify(httpResponse).end();
        verify(httpResponse, never()).sendFile(any());

        final String uidsCookie = captureAllCookies().get(0);
        final Uids decodedUids = decodeUids(uidsCookie);
        assertThat(decodedUids.getUids()).hasSize(1);
        assertThat(decodedUids.getUids().get("audienceNetwork").getUid()).isEqualTo("facebookUid");
    }

    @Test
    public void shouldRespondWithCookieFromRequestParam() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        // {"tempUIDs":{"rubicon":{"uid":"J5VLCWQP-26-CWFT"}}}
        given(uidsCookieService.toCookie(any())).willReturn(Cookie
                .cookie("uids", "eyJ0ZW1wVUlEcyI6eyJydWJpY29uIjp7InVpZCI6Iko1VkxDV1FQLTI2LUNXRlQifX19"));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("format")).willReturn("img");
        given(httpRequest.getParam("uid")).willReturn("J5VLCWQP-26-CWFT");

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(routingContext, never()).addCookie(any());
        verify(httpResponse).sendFile(any());

        final String uidsCookie = captureCookie();
        final Uids decodedUids = decodeUids(uidsCookie);
        assertThat(decodedUids.getUids()).hasSize(1);
        assertThat(decodedUids.getUids().get(RUBICON).getUid()).isEqualTo("J5VLCWQP-26-CWFT");
    }

    @Test
    public void shouldUpdateUidInCookieWithRequestValue() {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"));
        uids.put(ADNXS, UidWithExpiry.live("12345"));
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("uid")).willReturn("updatedUid");

        // {"tempUIDs":{"adnxs":{"uid":"12345"}, "rubicon":{"uid":"updatedUid"}}}
        given(uidsCookieService.toCookie(any())).willReturn(Cookie
                .cookie("uids", "eyJ0ZW1wVUlEcyI6eyJhZG54cyI6eyJ1aWQiOiIxMjM0NSJ9LCAicnViaWNvbiI6eyJ1aWQiOiJ1cGRhdGVkVW"
                        + "lkIn19fQ=="));

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse).end();
        verify(routingContext, never()).addCookie(any());

        final String uidsCookie = captureCookie();
        final Uids decodedUids = decodeUids(uidsCookie);
        assertThat(decodedUids.getUids()).hasSize(2);
        assertThat(decodedUids.getUids().get(RUBICON).getUid()).isEqualTo("updatedUid");
        assertThat(decodedUids.getUids().get(ADNXS).getUid()).isEqualTo("12345");
    }

    @Test
    public void shouldRespondWithCookieIfUserIsNotInGdprScope() {
        // given
        given(gdprService.resultByVendor(anySet(), anySet(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(GdprResponse.of(false, emptyMap(), null)));

        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        // {"tempUIDs":{"rubicon":{"uid":"J5VLCWQP-26-CWFT"}}}
        given(uidsCookieService.toCookie(any())).willReturn(Cookie
                .cookie("uids", "eyJ0ZW1wVUlEcyI6eyJydWJpY29uIjp7InVpZCI6Iko1VkxDV1FQLTI2LUNXRlQifX19"));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("uid")).willReturn("J5VLCWQP-26-CWFT");

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(routingContext, never()).addCookie(any());
        verify(httpResponse).end();

        final String uidsCookie = captureCookie();
        final Uids decodedUids = decodeUids(uidsCookie);
        assertThat(decodedUids.getUids()).hasSize(1);
        assertThat(decodedUids.getUids().get(RUBICON).getUid()).isEqualTo("J5VLCWQP-26-CWFT");
    }

    @Test
    public void shouldSendBadRequestIfUidsAuditCookieCannotBeCreated() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        given(uidsAuditCookieService.createUidsAuditCookie(any(), any(), any(), any(), any(), any()))
                .willThrow(new PreBidException("error"));

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end(
                "Error occurred on uids-audit cookie creation, uid cookie will not be set without it: error");
    }

    @Test
    public void shouldSendUidsAuditCookieWithUidsCookie() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        given(uidsAuditCookieService.createUidsAuditCookie(any(), any(), any(), any(), any(), any()))
                .willReturn(Cookie.cookie("uids-audit", "value").setDomain("rubicon"));

        // {"tempUIDs":{"adnxs":{"uid":"12345"}, "rubicon":{"uid":"updatedUid"}}}
        given(uidsCookieService.toCookie(any())).willReturn(Cookie
                .cookie("uids", "eyJ0ZW1wVUlEcyI6eyJhZG54cyI6eyJ1aWQiOiIxMjM0NSJ9LCAicnViaWNvbiI6eyJ1aWQiOiJ1cGRhdGVkVW"
                        + "lkIn19fQ=="));

        // when
        setuidHandler.handle(routingContext);

        // then
        final List<String> cookies = captureAllCookies();
        assertThat(cookies.get(0)).startsWith("uids=");
        assertThat(cookies.get(1)).startsWith("uids-audit=");
    }

    @Test
    public void shouldTolerateSendingUidsAuditCookieIfUserIsNotInGdprScope() {
        // given
        given(gdprService.resultByVendor(anySet(), anySet(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(GdprResponse.of(false, singletonMap(null, true), null)));

        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build()));

        given(uidsAuditCookieService.createUidsAuditCookie(any(), any(), any(), any(), any(), any()))
                .willReturn(Cookie.cookie("uids-audit", "value").setDomain("rubicon"));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        // {"tempUIDs":{"rubicon":{"uid":"J5VLCWQP-26-CWFT"}}}
        given(uidsCookieService.toCookie(any())).willReturn(Cookie
                .cookie("uids", "eyJ0ZW1wVUlEcyI6eyJydWJpY29uIjp7InVpZCI6Iko1VkxDV1FQLTI2LUNXRlQifX19"));

        // when
        setuidHandler.handle(routingContext);

        // then
        final String uidsCookie = captureAllCookies().get(0);
        assertThat(uidsCookie).startsWith("uids=");
    }

    @Test
    public void shouldSendBadRequestIfUidsAuditCookieCannotBeRetrievedForNonRubiconBidder() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(ADNXS, UidWithExpiry.live("12345"))).build()));

        given(httpRequest.getParam("bidder")).willReturn(ADNXS);
        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        given(uidsAuditCookieService.getUidsAudit(any(RoutingContext.class))).willThrow(new PreBidException("error"));

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end("Error retrieving of uids-audit cookie: error");
    }

    @Test
    public void shouldSendBadRequestIfUidsAuditCookieIsMissingForNonRubiconBidder() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(ADNXS, UidWithExpiry.live("12345"))).build()));

        given(httpRequest.getParam("bidder")).willReturn(ADNXS);
        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        given(uidsAuditCookieService.getUidsAudit(any(RoutingContext.class))).willReturn(null);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end("\"uids-audit\" cookie is missing, sync Rubicon bidder first");
    }

    @Test
    public void shouldNotRequireAccountForNonRubiconBidder() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(ADNXS, UidWithExpiry.live("12345"))).build()));

        given(httpRequest.getParam("bidder")).willReturn(ADNXS);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpRequest, times(0)).getParam(eq("account"));
    }

    @Test
    public void shouldUpdateOptOutsMetricIfOptedOut() {
        // given
        // this uids cookie value stands for {"optout": true}
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).optout(true).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(metrics).updateUserSyncOptoutMetric();
    }

    @Test
    public void shouldUpdateBadRequestsMetricIfBidderParamIsMissing() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(metrics).updateUserSyncBadRequestMetric();
    }

    @Test
    public void shouldNotSendResponseIfClientClosedConnection() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("uid")).willReturn("uid");

        given(routingContext.response().closed()).willReturn(true);

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(httpResponse, never()).end();
    }

    @Test
    public void shouldUpdateSetsMetric() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("uid")).willReturn("updatedUid");

        // when
        setuidHandler.handle(routingContext);

        // then
        verify(metrics).updateUserSyncSetsMetric(eq(RUBICON));
    }

    @Test
    public void shouldPassUnauthorizedEventToAnalyticsReporterIfOptedOut() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).optout(true).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        final SetuidEvent setuidEvent = captureSetuidEvent();
        assertThat(setuidEvent).isEqualTo(SetuidEvent.builder().status(401).build());
    }

    @Test
    public void shouldPassBadRequestEventToAnalyticsReporterIfBidderParamIsMissing() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.handle(routingContext);

        // then
        final SetuidEvent setuidEvent = captureSetuidEvent();
        assertThat(setuidEvent).isEqualTo(SetuidEvent.builder().status(400).build());
    }

    @Test
    public void shouldPassUnsuccessfulEventToAnalyticsReporterIfUidMissingInRequest() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        // when
        setuidHandler.handle(routingContext);

        // then
        final SetuidEvent setuidEvent = captureSetuidEvent();
        assertThat(setuidEvent).isEqualTo(SetuidEvent.builder()
                .status(200)
                .bidder(RUBICON)
                .success(false)
                .build());
    }

    @Test
    public void shouldPassUnsuccessfulEventToAnalyticsReporterIfFacebookSentinel() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build()));

        given(httpRequest.getParam("bidder")).willReturn("audienceNetwork");
        given(httpRequest.getParam("uid")).willReturn("0");

        given(uidsAuditCookieService.getUidsAudit(any(RoutingContext.class))).willReturn(UidAudit.builder().build());
        given(uidsAuditCookieService.createUidsAuditCookie(any(), any(), any(), any(), any(), any()))
                .willReturn(Cookie.cookie("uids-audit", "value").setDomain("rubicon"));

        // when
        setuidHandler.handle(routingContext);

        // then
        final SetuidEvent setuidEvent = captureSetuidEvent();
        assertThat(setuidEvent).isEqualTo(SetuidEvent.builder()
                .status(200)
                .bidder("audienceNetwork")
                .uid("0")
                .success(false)
                .build());
    }

    @Test
    public void shouldPassSuccessfulEventToAnalyticsReporter() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("uid")).willReturn("updatedUid");

        // when
        setuidHandler.handle(routingContext);

        // then
        final SetuidEvent setuidEvent = captureSetuidEvent();
        assertThat(setuidEvent).isEqualTo(SetuidEvent.builder()
                .status(200)
                .bidder(RUBICON)
                .uid("updatedUid")
                .success(true)
                .build());
    }

    private String captureCookie() {
        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(responseHeaders).add(eq(new AsciiString("Set-Cookie")), captor.capture());
        return captor.getValue();
    }

    private List<String> captureAllCookies() {
        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(responseHeaders, times(2)).add(eq(new AsciiString("Set-Cookie")), captor.capture());
        return captor.getAllValues();
    }

    private static Uids decodeUids(String value) {
        final String uids = value.substring(5).split(";")[0];
        return Json.decodeValue(Buffer.buffer(Base64.getUrlDecoder().decode(uids)), Uids.class);
    }

    private SetuidEvent captureSetuidEvent() {
        final ArgumentCaptor<SetuidEvent> setuidEventCaptor = ArgumentCaptor.forClass(SetuidEvent.class);
        verify(analyticsReporter).processEvent(setuidEventCaptor.capture());
        return setuidEventCaptor.getValue();
    }
}
