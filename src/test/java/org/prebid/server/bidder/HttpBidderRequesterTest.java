package org.prebid.server.bidder;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Deal;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Pmp;
import com.iab.openrtb.response.Bid;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.BDDMockito;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.BidderSeatBid;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.execution.Timeout;
import org.prebid.server.execution.TimeoutFactory;
import org.prebid.server.proto.openrtb.ext.response.ExtHttpCall;
import org.prebid.server.vertx.http.HttpClient;
import org.prebid.server.vertx.http.model.HttpClientResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

public class HttpBidderRequesterTest extends VertxTest {

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private Bidder<BidRequest> bidder;
    @Mock
    private HttpClient httpClient;
    @Mock
    private BidderErrorNotifier bidderErrorNotifier;

    private HttpBidderRequester httpBidderRequester;

    private Timeout timeout;
    private Timeout expiredTimeout;

    @Before
    public void setUp() {
        given(bidderErrorNotifier.processTimeout(any(), any())).will(invocation -> invocation.getArgument(0));

        final Clock clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        final TimeoutFactory timeoutFactory = new TimeoutFactory(clock);
        timeout = timeoutFactory.create(500L);
        expiredTimeout = timeoutFactory.create(clock.instant().minusMillis(1500L).toEpochMilli(), 1000L);

        httpBidderRequester = new HttpBidderRequester(httpClient, null, bidderErrorNotifier);
    }

    @Test
    public void shouldReturnFailedToRequestBidsErrorWhenBidderReturnsEmptyHttpRequestAndErrorLists() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(emptyList(), emptyList()));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout, false)
                        .result();

        // then
        assertThat(bidderSeatBid.getBids()).isEmpty();
        assertThat(bidderSeatBid.getHttpCalls()).isEmpty();
        assertThat(bidderSeatBid.getErrors())
                .containsOnly(BidderError.failedToRequestBids(
                        "The bidder failed to generate any bid requests, but also failed to generate an error"));
    }

    @Test
    public void shouldTolerateBidderReturningErrorsAndNoHttpRequests() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(emptyList(),
                asList(BidderError.badInput("error1"), BidderError.badInput("error2"))));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout, false)
                        .result();

        // then
        assertThat(bidderSeatBid.getBids()).isEmpty();
        assertThat(bidderSeatBid.getHttpCalls()).isEmpty();
        assertThat(bidderSeatBid.getErrors())
                .extracting(BidderError::getMessage).containsOnly("error1", "error2");
    }

    @Test
    public void shouldSendPopulatedPostRequest() {
        // given
        givenHttpClientReturnsResponse(200, null);

        final MultiMap headers = new CaseInsensitiveHeaders();
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri("uri")
                        .body("requestBody")
                        .headers(headers)
                        .build()),
                emptyList()));
        headers.add("header1", "value1");
        headers.add("header2", "value2");

        // when
        httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout, false);

        // then
        verify(httpClient).request(eq(HttpMethod.POST), eq("uri"), eq(headers), eq("requestBody"), eq(500L));
    }

    @Test
    public void shouldSendPopulatedGetRequestWithoutBody() {
        // given
        givenHttpClientReturnsResponse(200, null);

        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.GET)
                        .uri("uri")
                        .build()),
                emptyList()));

        // when
        httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout, false);

        // then
        verify(httpClient).request(any(), anyString(), any(), (String) isNull(), anyLong());
    }

    @Test
    public void shouldSendMultipleRequests() {
        // given
        givenHttpClientReturnsResponse(200, null);

        given(bidder.makeHttpRequests(any())).willReturn(Result.of(asList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build(),
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                emptyList()));

        // when
        httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout, false);

        // then
        verify(httpClient, times(2)).request(any(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    public void shouldReturnBidsCreatedByBidder() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                emptyList()));

        givenHttpClientReturnsResponse(200, "responseBody");

        final List<BidderBid> bids = asList(
                BidderBid.of(Bid.builder().impid("123").build(), null, null),
                BidderBid.of(Bid.builder().impid("456").build(), null, null));
        given(bidder.makeBids(any(), any())).willReturn(Result.of(bids, emptyList()));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout,
                        false).result();

        // then
        assertThat(bidderSeatBid.getBids()).containsOnlyElementsOf(bids);
    }

    @Test
    public void shouldNotWaitForResponsesWhenAllDealsIsGathered() {
        // given
        httpBidderRequester = new HttpBidderRequester(httpClient, new DealsBidderRequestCompletionTrackerFactory(),
                bidderErrorNotifier);

        final BidRequest bidRequest = bidRequestWithDeals("deal1", "deal2");
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(Arrays.asList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body("r1")
                        .headers(new CaseInsensitiveHeaders())
                        .payload(bidRequestWithDeals("deal1"))
                        .build(),
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body("r2")
                        .headers(new CaseInsensitiveHeaders())
                        .payload(bidRequestWithDeals("deal1"))
                        .build(),
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body("r3")
                        .headers(new CaseInsensitiveHeaders())
                        .payload(bidRequestWithDeals("deal2"))
                        .build(),
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body("r4")
                        .headers(new CaseInsensitiveHeaders())
                        .payload(bidRequestWithDeals("deal1"))
                        .build()),
                emptyList()));

        final HttpClientResponse respWithDeal1 = HttpClientResponse.of(200, null,
                "{\"seatbid\":[{\"bid\":[{\"dealid\":\"deal1\"}]}]}");
        final HttpClientResponse respWithDeal2 = HttpClientResponse.of(200, null,
                "{\"seatbid\":[{\"bid\":[{\"dealid\":\"deal2\"}]}]}");

        given(httpClient.request(any(), anyString(), any(), eq("r1"), anyLong()))
                .willReturn(Future.succeededFuture(respWithDeal1));
        given(httpClient.request(any(), anyString(), any(), eq("r2"), anyLong()))
                .willReturn(Promise.<HttpClientResponse>promise().future());
        given(httpClient.request(any(), anyString(), any(), eq("r3"), anyLong()))
                .willReturn(Future.succeededFuture(respWithDeal2));
        given(httpClient.request(any(), anyString(), any(), eq("r4"), anyLong()))
                .willReturn(Promise.<HttpClientResponse>promise().future());

        final BidderBid bidderBidDeal1 = BidderBid.of(Bid.builder().impid("deal1").dealid("deal1").build(), null, null);
        final BidderBid bidderBidDeal2 = BidderBid.of(Bid.builder().impid("deal2").dealid("deal2").build(), null, null);
        given(bidder.makeBids(any(), any())).willReturn(
                Result.of(singletonList(bidderBidDeal1), emptyList()),
                Result.of(singletonList(bidderBidDeal2), emptyList()));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, bidRequest, timeout, false).result();

        // then
        verify(bidder, times(1)).makeHttpRequests(any());
        verify(httpClient, times(4)).request(any(), any(), any(), anyString(), anyLong());
        verify(bidder, times(2)).makeBids(any(), any());

        assertThat(bidderSeatBid.getBids()).containsOnly(bidderBidDeal1, bidderBidDeal2);
    }

    @Test
    public void shouldFinishWhenAllDealRequestsAreFinishedAndNoDealsProvided() {
        // given
        final BidRequest bidRequest = bidRequestWithDeals("deal1", "deal2", "deal2");
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(Arrays.asList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body("r1")
                        .headers(new CaseInsensitiveHeaders())
                        .payload(bidRequestWithDeals("deal1"))
                        .build(),
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body("r2")
                        .headers(new CaseInsensitiveHeaders())
                        .payload(bidRequestWithDeals("deal2"))
                        .build(),
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body("r3")
                        .headers(new CaseInsensitiveHeaders())
                        .payload(bidRequestWithDeals("deal2"))
                        .build(),
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body("r4")
                        .headers(new CaseInsensitiveHeaders())
                        .payload(bidRequestWithDeals("deal2"))
                        .build()),
                emptyList()));

        givenHttpClientReturnsResponse(200, "responseBody");

        final BidderBid bidderBid = BidderBid.of(Bid.builder().dealid("deal2").build(), null, null);
        given(bidder.makeBids(any(), any())).willReturn(Result.of(singletonList(bidderBid), emptyList()));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, bidRequest, timeout, false).result();

        // then
        verify(bidder, times(1)).makeHttpRequests(any());
        verify(httpClient, times(4)).request(any(), any(), any(), anyString(), anyLong());
        verify(bidder, times(4)).makeBids(any(), any());

        assertThat(bidderSeatBid.getBids()).contains(bidderBid, bidderBid, bidderBid, bidderBid);
    }

    @Test
    public void shouldReturnFullDebugInfoIfDebugEnabled() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(asList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri("uri1")
                        .body("requestBody1")
                        .headers(new CaseInsensitiveHeaders())
                        .build(),
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri("uri2")
                        .body("requestBody2")
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                emptyList()));

        givenHttpClientReturnsResponses(
                HttpClientResponse.of(200, null, "responseBody1"),
                HttpClientResponse.of(200, null, "responseBody2"));

        given(bidder.makeBids(any(), any())).willReturn(Result.of(emptyList(), emptyList()));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout,
                        true).result();

        // then
        assertThat(bidderSeatBid.getHttpCalls()).hasSize(2).containsOnly(
                ExtHttpCall.builder().uri("uri1").requestbody("requestBody1").responsebody("responseBody1")
                        .status(200).build(),
                ExtHttpCall.builder().uri("uri2").requestbody("requestBody2").responsebody("responseBody2")
                        .status(200).build());
    }

    @Test
    public void shouldReturnPartialDebugInfoIfDebugEnabledAndGlobalTimeoutAlreadyExpired() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri("uri1")
                        .body("requestBody1")
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                emptyList()));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), expiredTimeout,
                        true).result();

        // then
        assertThat(bidderSeatBid.getHttpCalls()).hasSize(1).containsOnly(
                ExtHttpCall.builder().uri("uri1").requestbody("requestBody1").build());
    }

    @Test
    public void shouldReturnPartialDebugInfoIfDebugEnabledAndHttpErrorOccurs() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri("uri1")
                        .body("requestBody1")
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                emptyList()));

        givenHttpClientProducesException(new RuntimeException("Request exception"));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout,
                        true).result();

        // then
        assertThat(bidderSeatBid.getHttpCalls()).hasSize(1).containsOnly(
                ExtHttpCall.builder().uri("uri1").requestbody("requestBody1").build());
    }

    @Test
    public void shouldReturnFullDebugInfoIfDebugEnabledAndErrorStatus() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri("uri1")
                        .body("requestBody1")
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                emptyList()));

        givenHttpClientReturnsResponses(HttpClientResponse.of(500, null, "responseBody1"));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), timeout,
                        true).result();

        // then
        assertThat(bidderSeatBid.getHttpCalls()).hasSize(1).containsOnly(
                ExtHttpCall.builder().uri("uri1").requestbody("requestBody1").responsebody("responseBody1")
                        .status(500).build());
        assertThat(bidderSeatBid.getErrors()).hasSize(1)
                .extracting(BidderError::getMessage).containsOnly(
                "Unexpected status code: 500. Run with request.test = 1 for more info");
    }

    @Test
    public void shouldTolerateAlreadyExpiredGlobalTimeout() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                emptyList()));

        // when
        final BidderSeatBid bidderSeatBid =
                httpBidderRequester.requestBids(bidder, BidRequest.builder().imp(emptyList()).build(), expiredTimeout,
                        false).result();

        // then
        assertThat(bidderSeatBid.getErrors()).hasSize(1)
                .extracting(BidderError::getMessage)
                .containsOnly("Timeout has been exceeded");
        verifyZeroInteractions(httpClient);
    }

    @Test
    public void shouldNotifyBidderOfTimeout() {
        // given
        final HttpRequest<BidRequest> httpRequest = HttpRequest.<BidRequest>builder()
                .method(HttpMethod.POST)
                .uri(EMPTY)
                .body(EMPTY)
                .build();

        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(httpRequest), null));

        given(httpClient.request(any(), anyString(), any(), (String) any(), anyLong()))
                // bidder request
                .willReturn(Future.failedFuture(new TimeoutException("Timeout exception")));

        // when
        httpBidderRequester.requestBids(bidder, BidRequest.builder().build(), timeout, false);

        // then
        verify(bidderErrorNotifier).processTimeout(any(), same(bidder));
    }

    @Test
    public void shouldTolerateMultipleErrors() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(asList(
                // this request will fail with response exception
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build(),
                // this request will fail with timeout
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build(),
                // this request will fail with 500 status
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build(),
                // this request will fail with 400 status
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build(),
                // this request will get 204 status
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build(),
                // finally this request will succeed
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                singletonList(BidderError.badInput("makeHttpRequestsError"))));

        given(httpClient.request(any(), anyString(), any(), anyString(), anyLong()))
                // simulate response error for the first request
                .willReturn(Future.failedFuture(new RuntimeException("Response exception")))
                // simulate timeout for the second request
                .willReturn(Future.failedFuture(new TimeoutException("Timeout exception")))
                // simulate 500 status
                .willReturn(Future.succeededFuture(HttpClientResponse.of(500, null, EMPTY)))
                // simulate 400 status
                .willReturn(Future.succeededFuture(HttpClientResponse.of(400, null, EMPTY)))
                // simulate 204 status
                .willReturn(Future.succeededFuture(HttpClientResponse.of(204, null, EMPTY)))
                // simulate 200 status
                .willReturn(Future.succeededFuture(HttpClientResponse.of(200, null, EMPTY)));

        given(bidder.makeBids(any(), any())).willReturn(
                Result.of(singletonList(BidderBid.of(Bid.builder().impid("123").build(), null, null)),
                        singletonList(BidderError.badServerResponse("makeBidsError"))));

        // when
        final BidderSeatBid bidderSeatBid = httpBidderRequester
                .requestBids(bidder, BidRequest.builder().imp(emptyList()).test(1).build(), timeout, false)
                .result();

        // then
        // only one calls is expected (200) since other requests have failed with errors.
        verify(bidder, times(1)).makeBids(any(), any());
        assertThat(bidderSeatBid.getBids()).hasSize(1);
        assertThat(bidderSeatBid.getErrors()).containsOnly(
                BidderError.badInput("makeHttpRequestsError"),
                BidderError.generic("Response exception"),
                BidderError.timeout("Timeout exception"),
                BidderError.badServerResponse("Unexpected status code: 500. Run with request.test = 1 for more info"),
                BidderError.badInput("Unexpected status code: 400. Run with request.test = 1 for more info"),
                BidderError.badServerResponse("makeBidsError"));
    }

    @Test
    public void shouldNotMakeBidsIfResponseStatusIs204() {
        // given
        given(bidder.makeHttpRequests(any())).willReturn(Result.of(singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(EMPTY)
                        .body(EMPTY)
                        .headers(new CaseInsensitiveHeaders())
                        .build()),
                emptyList()));

        givenHttpClientReturnsResponse(204, EMPTY);

        // when
        httpBidderRequester.requestBids(bidder, BidRequest.builder().test(1).build(), timeout, false);

        // then
        verify(bidder, never()).makeBids(any(), any());
    }

    private static BidRequest bidRequestWithDeals(String... ids) {
        final List<Imp> impsWithDeals = Arrays.stream(ids)
                .map(HttpBidderRequesterTest::impWithDeal)
                .collect(Collectors.toList());
        return BidRequest.builder().imp(impsWithDeals).build();
    }

    private static Imp impWithDeal(String dealId) {
        return Imp.builder()
                .id(dealId)
                .pmp(Pmp.builder()
                        .deals(singletonList(Deal.builder().id(dealId).build()))
                        .build())
                .build();
    }

    private void givenHttpClientReturnsResponse(int statusCode, String response) {
        given(httpClient.request(any(), anyString(), any(), (String) any(), anyLong()))
                .willReturn(Future.succeededFuture(HttpClientResponse.of(statusCode, null, response)));
    }

    private void givenHttpClientProducesException(Throwable throwable) {
        given(httpClient.request(any(), anyString(), any(), anyString(), anyLong()))
                .willReturn(Future.failedFuture(throwable));
    }

    private void givenHttpClientReturnsResponses(HttpClientResponse... httpClientResponses) {
        BDDMockito.BDDMyOngoingStubbing<Future<HttpClientResponse>> stubbing =
                given(httpClient.request(any(), anyString(), any(), anyString(), anyLong()));

        // setup multiple answers
        for (HttpClientResponse httpClientResponse : httpClientResponses) {
            stubbing = stubbing.willReturn(Future.succeededFuture(httpClientResponse));
        }
    }
}
