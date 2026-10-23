package vn.ttcs.recruitment.offerdelivery.http;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.ttcs.recruitment.application.ApplicationClientIpResolver;
import vn.ttcs.recruitment.common.RetryAfterApiException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OfferRequestFilterTest {
    private final ObjectMapper json = JsonMapper.builder().build();
    private final OfferRequestQuota quota = mock(OfferRequestQuota.class);
    private final ApplicationClientIpResolver addresses = new ApplicationClientIpResolver("", "NONE");

    private OfferRequestFilter filter(boolean enabled, int permits) {
        return new OfferRequestFilter(json, addresses, quota, enabled, permits);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 17})
    void invalidConcurrencyStopsStartup(int permits) {
        assertThatThrownBy(() -> filter(true, permits)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void onlyExactLookupAndRespondPostsAreGuarded() throws Exception {
        var filter = filter(true, 1);
        for (String path : new String[] {OfferRoutes.LOOKUP, OfferRoutes.RESPOND}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("POST", path, "application/json"), response, (req, res) -> { });
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            for (String method : new String[] {"GET", "HEAD", "PUT", "PATCH"}) {
                var bypass = new MockHttpServletResponse();
                filter.doFilter(request(method, path, "text/plain"), bypass, (req, res) -> res.setContentType("text/plain"));
                assertThat(bypass.getHeader("Cache-Control")).isNull();
            }
            var suffix = new MockHttpServletResponse();
            filter.doFilter(request("POST", path + "/", "text/plain"), suffix, (req, res) -> { });
            assertThat(suffix.getHeader("Cache-Control")).isNull();
        }
        for (String path : new String[] {"/api/v1/public/offers/reply", "/api/v1/public/offers/reply.js"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("GET", path, null), response, (req, res) -> { });
            assertThat(response.getHeader("Cache-Control")).isNull();
        }
        verify(quota, times(2)).checkAndRecord("192.0.2.7");
    }

    @Test void disabledFeatureFailsBeforeReadingTheBodyOrDatabase() throws Exception {
        var request = request("POST", OfferRoutes.LOOKUP, "application/json");
        assertRefused(filter(false, 1), request, 503, "OFFER_UNAVAILABLE");
        verifyNoInteractions(quota);
    }

    @Test void invalidShapeTakesNoQuotaAndDoesNotEchoTheToken() throws Exception {
        var filter = filter(true, 1);
        var query = request("POST", OfferRoutes.RESPOND, "application/json");
        query.setQueryString("token=private-token-value");
        assertRefused(filter, query, 400, "VALIDATION_ERROR");
        for (String type : new String[] {null, "text/plain", "application/jsonp", "broken type"}) {
            assertRefused(filter, request("POST", OfferRoutes.LOOKUP, type), 415,
                    "OFFER_JSON_REQUIRED");
        }
        var oversized = request("POST", OfferRoutes.RESPOND, "application/json");
        oversized.setContent(new byte[8193]);
        assertRefused(filter, oversized, 413, "OFFER_REQUEST_TOO_LARGE");
        verifyNoInteractions(quota);
    }

    @Test void chunkedBodyIsBoundedAndCopiedForTheController() throws Exception {
        var filter = filter(true, 1);
        var accepted = new AtomicReference<byte[]>();
        byte[] maximum = new byte[8192];
        filter.doFilter(chunked(maximum), new MockHttpServletResponse(), (req, res) -> {
            accepted.set(req.getInputStream().readAllBytes());
            assertThatThrownBy(req::getReader).isInstanceOf(IllegalStateException.class);
        });
        assertThat(accepted.get()).isEqualTo(maximum);
        var excessive = chunked(new byte[8292]);
        assertRefused(filter, excessive, 400, "INVALID_JSON");
        assertThat(excessive.getInputStream().available()).isEqualTo(99);
        verify(quota).checkAndRecord("192.0.2.7");
    }

    @Test void brokenBodyDoesNotUseQuotaAndReaderPreservesUtf8() throws Exception {
        var filter = filter(true, 1);
        var broken = new MockHttpServletRequest("POST", OfferRoutes.LOOKUP) {
            @Override public ServletInputStream getInputStream() {
                return new BodyStream(new byte[0], () -> { throw new IOException("private-token-value"); });
            }
        };
        broken.setServletPath(OfferRoutes.LOOKUP);
        broken.setContentType("application/json");
        assertRefused(filter, broken, 400, "INVALID_JSON");
        verifyNoInteractions(quota);
        var utf8 = request("POST", OfferRoutes.RESPOND, "application/json;charset=UTF-8");
        String text = "{\"reason\":\"Đổi thời gian\"}";
        utf8.setContent(text.getBytes(StandardCharsets.UTF_8));
        var received = new AtomicReference<String>();
        filter.doFilter(utf8, new MockHttpServletResponse(), (req, res) -> {
            received.set(req.getReader().readLine());
            assertThatThrownBy(req::getInputStream).isInstanceOf(IllegalStateException.class);
        });
        assertThat(received.get()).isEqualTo(text);
    }

    @Test void quotaAndUnexpectedStorageFailuresStayClosedAndReturnRetryAfter() throws Exception {
        var filter = filter(true, 1);
        doThrow(new RetryAfterApiException(HttpStatus.TOO_MANY_REQUESTS,
                "OFFER_RATE_LIMIT_EXCEEDED", "Thử lại sau.", 37))
                .when(quota).checkAndRecord("192.0.2.7");
        var limited = assertRefused(filter, request("POST", OfferRoutes.LOOKUP, "application/json"),
                429, "OFFER_RATE_LIMIT_EXCEEDED");
        assertThat(limited.getHeader("Retry-After")).isEqualTo("37");
        doThrow(new IllegalStateException("private-token-value SQL details"))
                .when(quota).checkAndRecord("192.0.2.7");
        assertRefused(filter, request("POST", OfferRoutes.RESPOND, "application/json"),
                503, "OFFER_UNAVAILABLE");
    }

    @Test void gateRefusesConcurrentRequestsBeforeQuotaAndReturnsItsPermitAfterFailure() throws Exception {
        var filter = filter(true, 1);
        var nested = new MockHttpServletResponse();
        var nestedCalls = new AtomicInteger();
        filter.doFilter(request("POST", OfferRoutes.LOOKUP, "application/json"), new MockHttpServletResponse(),
                (req, res) -> filter.doFilter(request("POST", OfferRoutes.RESPOND, "application/json"), nested,
                        (r, s) -> nestedCalls.incrementAndGet()));
        assertThat(nestedCalls).hasValue(0);
        assertThat(nested.getStatus()).isEqualTo(503);
        assertThat(nested.getHeader("Retry-After")).isEqualTo("1");
        verify(quota).checkAndRecord("192.0.2.7");
        assertThatThrownBy(() -> filter.doFilter(request("POST", OfferRoutes.LOOKUP, "application/json"),
                new MockHttpServletResponse(), (req, res) -> { throw new IllegalStateException("controller failed"); }))
                .isInstanceOf(IllegalStateException.class);
        var received = new AtomicInteger();
        filter.doFilter(request("POST", OfferRoutes.LOOKUP, "application/json"), new MockHttpServletResponse(),
                (req, res) -> received.incrementAndGet());
        assertThat(received).hasValue(1);
    }

    @Test void bodyArrivesBeforeTheGateIsTakenAndUnknownTokensStillConsumeQuota() throws Exception {
        var filter = filter(true, 1);
        var duringBody = new AtomicInteger();
        var slow = new MockHttpServletRequest("POST", OfferRoutes.LOOKUP) {
            private final ServletInputStream stream = new BodyStream("{}".getBytes(StandardCharsets.UTF_8), () -> {
                try {
                    filter.doFilter(request("POST", OfferRoutes.LOOKUP, "application/json"),
                            new MockHttpServletResponse(), (req, res) -> duringBody.incrementAndGet());
                } catch (jakarta.servlet.ServletException failure) { throw new IOException(failure); }
            });
            @Override public ServletInputStream getInputStream() { return stream; }
        };
        slow.setServletPath(OfferRoutes.LOOKUP);
        slow.setRemoteAddr("192.0.2.7");
        slow.setContentType("application/json");
        var unknown = new MockHttpServletResponse();
        filter.doFilter(slow, unknown, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(404));
        assertThat(duringBody).hasValue(1);
        assertThat(unknown.getStatus()).isEqualTo(404);
        verify(quota, times(2)).checkAndRecord("192.0.2.7");
    }

    @Test void clientCannotForgeForwardingHeadersButAnExplicitlyTrustedProxyCanSupplyItsClient() throws Exception {
        var forged = request("POST", OfferRoutes.LOOKUP, "application/json");
        forged.addHeader("X-Forwarded-For", "198.51.100.8");
        filter(true, 1).doFilter(forged, new MockHttpServletResponse(), (req, res) -> { });
        verify(quota).checkAndRecord("192.0.2.7");
        var trusted = new OfferRequestFilter(json,
                new ApplicationClientIpResolver("192.0.2.7/32", "NONE"), quota, true, 1);
        trusted.doFilter(forged, new MockHttpServletResponse(), (req, res) -> { });
        verify(quota).checkAndRecord("198.51.100.8");
    }

    private MockHttpServletResponse assertRefused(OfferRequestFilter filter,
            MockHttpServletRequest request, int status, String code) throws Exception {
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();
        filter.doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertThat(calls).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo(code);
        assertThat(response.getContentAsString()).doesNotContain("private-token-value", "SQL details");
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String type) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr("192.0.2.7");
        if (type != null) request.setContentType(type);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest chunked(byte[] body) {
        var request = new MockHttpServletRequest("POST", OfferRoutes.LOOKUP) {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setServletPath(OfferRoutes.LOOKUP);
        request.setRemoteAddr("192.0.2.7");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    private static final class BodyStream extends ServletInputStream {
        interface Action { void run() throws IOException; }
        private final ByteArrayInputStream bytes;
        private Action firstRead;
        BodyStream(byte[] content, Action firstRead) { bytes = new ByteArrayInputStream(content); this.firstRead = firstRead; }
        @Override public int read() throws IOException {
            if (firstRead != null) { Action action = firstRead; firstRead = null; action.run(); }
            return bytes.read();
        }
        @Override public boolean isFinished() { return bytes.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
    }
}
