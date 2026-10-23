package vn.ttcs.recruitment.application;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 339: the guard of the public tracking POSTs, without a server. The HTTP behaviour is covered end to end in
// ApplicationLookupIntegrationTest.
class ApplicationTrackingRequestFilterTest {
    private final ObjectMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(ints = {0, 17, -1})
    void aConcurrencyOutsideOneToSixteenStopsStartup(int maxConcurrent) {
        assertThatThrownBy(() -> new ApplicationTrackingRequestFilter(json, maxConcurrent))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Application tracking concurrency must be 1..16");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 16})
    void theBoundsThemselvesAreAccepted(int maxConcurrent) {
        new ApplicationTrackingRequestFilter(json, maxConcurrent);
    }

    @Test
    void onlyTheExactPostOfTheLookupIsGuarded() throws Exception {
        var filter = new ApplicationTrackingRequestFilter(json, 1);
        for (var request : new MockHttpServletRequest[] {request("GET", ApplicationTrackingRoutes.LOOKUP, "text/plain"),
                request("POST", ApplicationTrackingRoutes.LOOKUP + "/", "text/plain"),
                request("POST", "/api/v1/public/applications", "text/plain")}) {
            var response = new MockHttpServletResponse();
            var calls = new AtomicInteger();
            filter.doFilter(request, response, (req, res) -> calls.incrementAndGet());
            assertThat(calls).hasValue(1);
            assertThat(response.getHeader("Cache-Control")).isNull();
        }
    }

    // Task 345: the exact POST of the withdrawal is guarded like the lookup; its frontend PATCH, a GET and a longer path
    // are not this filter's (default deny refuses them).
    @Test
    void theExactPostOfTheWithdrawalIsGuardedToo() throws Exception {
        var filter = new ApplicationTrackingRequestFilter(json, 1);
        assertRefused(filter, request("POST", ApplicationTrackingRoutes.WITHDRAW, "text/plain"), 415,
                "APPLICATION_TRACKING_JSON_REQUIRED");
        var guarded = new MockHttpServletResponse();
        filter.doFilter(request("POST", ApplicationTrackingRoutes.WITHDRAW, "application/json"), guarded,
                (req, res) -> { });
        assertThat(guarded.getHeader("Cache-Control")).isEqualTo("no-store");
        for (var request : new MockHttpServletRequest[] {request("GET", ApplicationTrackingRoutes.WITHDRAW, "text/plain"),
                request("PATCH", ApplicationTrackingRoutes.WITHDRAW, "text/plain"),
                request("POST", ApplicationTrackingRoutes.WITHDRAW + "/", "text/plain"),
                request("PATCH", "/api/v1/public/applications/code/withdraw", "text/plain")}) {
            var response = new MockHttpServletResponse();
            var calls = new AtomicInteger();
            filter.doFilter(request, response, (req, res) -> calls.incrementAndGet());
            assertThat(calls).as(request.getMethod() + " " + request.getServletPath()).hasValue(1);
            assertThat(response.getHeader("Cache-Control")).isNull();
        }
    }

    @Test
    void refusalsAreWrittenBeforeTheChainWithNoStore() throws Exception {
        var filter = new ApplicationTrackingRequestFilter(json, 1);
        var query = request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json");
        query.setQueryString("trackingCode=secret");
        assertRefused(filter, query, 400, "VALIDATION_ERROR");
        assertRefused(filter, request("POST", ApplicationTrackingRoutes.LOOKUP, "text/plain"), 415,
                "APPLICATION_TRACKING_JSON_REQUIRED");
        assertRefused(filter, request("POST", ApplicationTrackingRoutes.LOOKUP, null), 415,
                "APPLICATION_TRACKING_JSON_REQUIRED");
        assertRefused(filter, request("POST", ApplicationTrackingRoutes.LOOKUP, "not a type"), 415,
                "APPLICATION_TRACKING_JSON_REQUIRED");
        var large = request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json");
        large.setContent(new byte[ApplicationTrackingRequestFilter.MAX_BODY_BYTES + 1]);
        assertRefused(filter, large, 413, "APPLICATION_TRACKING_REQUEST_TOO_LARGE");
        // Every refusal released nothing it did not take: the only permit is still free.
        var accepted = new AtomicInteger();
        filter.doFilter(request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json;charset=UTF-8"),
                new MockHttpServletResponse(), (req, res) -> accepted.incrementAndGet());
        assertThat(accepted).hasValue(1);
    }

    @Test
    void aRequestBeyondTheGateIsBusyAndThePermitIsReturnedAfterwards() throws Exception {
        var filter = new ApplicationTrackingRequestFilter(json, 1);
        var inner = new MockHttpServletResponse();
        var innerChain = new AtomicInteger();
        filter.doFilter(request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json"), new MockHttpServletResponse(),
                // While the first request is in the chain, a second one finds no permit.
                (req, res) -> filter.doFilter(request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json"), inner,
                        (r, s) -> innerChain.incrementAndGet()));
        assertThat(innerChain).hasValue(0);
        assertThat(inner.getStatus()).isEqualTo(503);
        assertThat(inner.getHeader("Retry-After")).isEqualTo("1");
        assertThat(inner.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(inner.getContentAsString()).get("code").asString()).isEqualTo("APPLICATION_TRACKING_BUSY");
        // A failing chain still returns its permit.
        assertThatThrownBy(() -> filter.doFilter(request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json"),
                new MockHttpServletResponse(), (req, res) -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        var after = new AtomicInteger();
        filter.doFilter(request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json"), new MockHttpServletResponse(),
                (req, res) -> after.incrementAndGet());
        assertThat(after).hasValue(1);
    }

    // The filter reads the body itself, before the gate: 8 KiB pass, one byte more is refused like an unreadable body,
    // without reading further, and the refusal takes no permit.
    @Test
    void aBodyWithoutLengthIsCutAfterEightKibibytes() throws Exception {
        var filter = new ApplicationTrackingRequestFilter(json, 1);
        var exact = chunked(new byte[ApplicationTrackingRequestFilter.MAX_BODY_BYTES]);
        var read = new AtomicInteger();
        filter.doFilter(exact, new MockHttpServletResponse(),
                (req, res) -> read.set(req.getInputStream().readAllBytes().length));
        assertThat(read).hasValue(ApplicationTrackingRequestFilter.MAX_BODY_BYTES);

        var tooLong = chunked(new byte[ApplicationTrackingRequestFilter.MAX_BODY_BYTES + 100]);
        var body = assertRefused(filter, tooLong, 400, "INVALID_JSON");
        assertThat(body.get("message").asString()).isEqualTo("Nội dung yêu cầu không đúng định dạng JSON.");
        assertThat(tooLong.getInputStream().available()).isEqualTo(99);

        var accepted = new AtomicInteger();
        filter.doFilter(request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json"), new MockHttpServletResponse(),
                (req, res) -> accepted.incrementAndGet());
        assertThat(accepted).hasValue(1);
    }

    // A client that stops sending, or goes away, while its body is read gets the same 400; the permit stays free.
    @Test
    void aBrokenBodyIsAnUnreadableBodyAndTakesNoPermit() throws Exception {
        var filter = new ApplicationTrackingRequestFilter(json, 1);
        var broken = new MockHttpServletRequest("POST", ApplicationTrackingRoutes.LOOKUP) {
            @Override public ServletInputStream getInputStream() {
                return new BodyStream(new byte[0], () -> { throw new IOException("Connection reset"); });
            }
        };
        broken.setServletPath(ApplicationTrackingRoutes.LOOKUP);
        broken.setContentType("application/json");
        assertRefused(filter, broken, 400, "INVALID_JSON");

        var accepted = new AtomicInteger();
        filter.doFilter(request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json"), new MockHttpServletResponse(),
                (req, res) -> accepted.incrementAndGet());
        assertThat(accepted).hasValue(1);
    }

    // The permit is taken only once the whole body is in memory: while the first request's body is still arriving
    // (here: during its first read), another request gets the only permit and reaches the chain. The chain of the
    // first request still reads its whole body.
    @Test
    void theBodyIsReadBeforeThePermitIsTaken() throws Exception {
        var filter = new ApplicationTrackingRequestFilter(json, 1);
        byte[] content = "{\"trackingCode\":\"abc\",\"email\":\"a@example.test\"}".getBytes(StandardCharsets.UTF_8);
        var other = new MockHttpServletResponse();
        var otherChain = new AtomicInteger();
        var slow = new MockHttpServletRequest("POST", ApplicationTrackingRoutes.LOOKUP) {
            private ServletInputStream stream;

            @Override public ServletInputStream getInputStream() {
                if (stream == null) {
                    stream = new BodyStream(content, () -> {
                        try {
                            filter.doFilter(request("POST", ApplicationTrackingRoutes.LOOKUP, "application/json"), other,
                                    (req, res) -> otherChain.incrementAndGet());
                        } catch (ServletException exception) {
                            throw new IOException(exception);
                        }
                    });
                }
                return stream;
            }
        };
        slow.setServletPath(ApplicationTrackingRoutes.LOOKUP);
        slow.setContentType("application/json");
        var received = new AtomicReference<byte[]>();
        filter.doFilter(slow, new MockHttpServletResponse(), (req, res) -> received.set(req.getInputStream().readAllBytes()));

        assertThat(otherChain).hasValue(1);
        assertThat(other.getStatus()).isEqualTo(200);
        assertThat(other.getHeader("Retry-After")).isNull();
        assertThat(received.get()).isEqualTo(content);
    }

    // The chain reads the bytes the filter has read, as a stream or as text in the request's charset (UTF-8 when none
    // is given), and only one of the two, as with a real request.
    @Test
    void theChainReadsTheBodyAsAStreamOrAsTextInTheRequestCharset() throws Exception {
        var filter = new ApplicationTrackingRequestFilter(json, 1);
        String text = "{\"email\":\"Nguy" + Character.toString(0x1EC5) + "n" + Character.toString(0x00E9) + "@example.test\"}";

        var utf8 = chunked(text.getBytes(StandardCharsets.UTF_8));
        var asText = new AtomicReference<String>();
        filter.doFilter(utf8, new MockHttpServletResponse(), (req, res) -> {
            asText.set(req.getReader().readLine());
            assertThatThrownBy(req::getInputStream).isInstanceOf(IllegalStateException.class);
        });
        assertThat(asText).hasValue(text);

        String latin = "{\"email\":\"Andr" + Character.toString(0x00E9) + "@example.test\"}";
        var latin1 = chunked(latin.getBytes(StandardCharsets.ISO_8859_1));
        latin1.setContentType("application/json;charset=ISO-8859-1");
        var asLatin = new AtomicReference<String>();
        filter.doFilter(latin1, new MockHttpServletResponse(), (req, res) -> asLatin.set(req.getReader().readLine()));
        assertThat(asLatin).hasValue(latin);

        var asBytes = new AtomicReference<byte[]>();
        filter.doFilter(chunked(text.getBytes(StandardCharsets.UTF_8)), new MockHttpServletResponse(), (req, res) -> {
            var stream = req.getInputStream();
            assertThat(stream.isFinished()).isFalse();
            asBytes.set(stream.readAllBytes());
            assertThat(stream.isFinished()).isTrue();
            assertThat(req.getInputStream()).isSameAs(stream);
            assertThatThrownBy(req::getReader).isInstanceOf(IllegalStateException.class);
        });
        assertThat(asBytes.get()).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode assertRefused(ApplicationTrackingRequestFilter filter, MockHttpServletRequest request, int status,
            String code) throws Exception {
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();
        FilterChain chain = (ServletRequest req, ServletResponse res) -> calls.incrementAndGet();
        filter.doFilter(request, response, chain);
        assertThat(calls).as(code).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(response.getContentType()).startsWith("application/json");
        var body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.properties()).hasSize(3);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret");
        return body;
    }

    // A body that runs onFirstRead before its first byte is handed out (to act while the body is "still arriving").
    private static final class BodyStream extends ServletInputStream {
        interface Action { void run() throws IOException; }

        private final ByteArrayInputStream bytes;
        private Action onFirstRead;

        BodyStream(byte[] content, Action onFirstRead) {
            bytes = new ByteArrayInputStream(content);
            this.onFirstRead = onFirstRead;
        }

        @Override public int read() throws IOException {
            if (onFirstRead != null) {
                var action = onFirstRead;
                onFirstRead = null;
                action.run();
            }
            return bytes.read();
        }

        @Override public boolean isFinished() { return bytes.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
    }

    private static MockHttpServletRequest request(String method, String path, String contentType) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (contentType != null) request.setContentType(contentType);
        return request;
    }

    // A request whose length is unknown, as with Transfer-Encoding: chunked.
    private static MockHttpServletRequest chunked(byte[] body) {
        var request = new MockHttpServletRequest("POST", ApplicationTrackingRoutes.LOOKUP) {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setServletPath(ApplicationTrackingRoutes.LOOKUP);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
