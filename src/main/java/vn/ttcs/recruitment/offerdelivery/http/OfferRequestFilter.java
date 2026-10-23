package vn.ttcs.recruitment.offerdelivery.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.application.ApplicationClientIpResolver;
import vn.ttcs.recruitment.common.ApiError;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.RetryAfterApiException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

/** The offer token stays in bounded JSON. The database gate is held only after the complete body arrives. */
@Component
public class OfferRequestFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 8192;
    private final ObjectMapper json;
    private final ApplicationClientIpResolver addresses;
    private final OfferRequestQuota quota;
    private final boolean enabled;
    private final Semaphore permits;

    public OfferRequestFilter(ObjectMapper json, ApplicationClientIpResolver addresses,
            OfferRequestQuota quota,
            @Value("${app.offers.enabled:false}") boolean enabled,
            @Value("${app.offers.max-concurrent:4}") int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 16) {
            throw new IllegalArgumentException("Offer response concurrency must be 1..16");
        }
        this.json = json; this.addresses = addresses; this.quota = quota; this.enabled = enabled;
        permits = new Semaphore(maxConcurrent);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !OfferRoutes.isPublicPost(request);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        byte[] body;
        try {
            if (!enabled) throw OfferRequestQuota.unavailable();
            if (request.getQueryString() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        "Không gửi mã thư offer trên đường dẫn.");
            }
            MediaType type;
            try { type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType()); }
            catch (IllegalArgumentException failure) { type = MediaType.APPLICATION_OCTET_STREAM; }
            if (!"application".equals(type.getType()) || !"json".equals(type.getSubtype())) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "OFFER_JSON_REQUIRED",
                        "Vui lòng gửi dữ liệu dạng JSON.");
            }
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "OFFER_REQUEST_TOO_LARGE",
                        "Dữ liệu gửi lên quá lớn.");
            }
            body = body(request);
        } catch (ApiException failure) {
            write(response, failure);
            return;
        } catch (RuntimeException failure) {
            write(response, OfferRequestQuota.unavailable());
            return;
        }
        if (!permits.tryAcquire()) {
            write(response, new RetryAfterApiException(HttpStatus.SERVICE_UNAVAILABLE, "OFFER_BUSY",
                    "Hệ thống đang bận. Vui lòng thử lại sau.", 1));
            return;
        }
        try {
            try {
                quota.checkAndRecord(addresses.resolve(request));
            } catch (ApiException failure) {
                write(response, failure);
                return;
            } catch (RuntimeException failure) {
                write(response, OfferRequestQuota.unavailable());
                return;
            }
            chain.doFilter(new BodyRequest(request, body), response);
        } finally {
            permits.release();
        }
    }

    private static byte[] body(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length <= MAX_BODY_BYTES) return body;
        } catch (IOException failure) { /* Broken or oversized JSON has the same bounded failure. */ }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Nội dung yêu cầu không đúng định dạng JSON.");
    }

    private void write(HttpServletResponse response, ApiException failure) throws IOException {
        response.setStatus(failure.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (failure instanceof RetryAfterApiException limited) {
            response.setHeader("Retry-After", Long.toString(limited.getRetryAfterSeconds()));
        }
        response.getWriter().write(json.writeValueAsString(
                new ApiError(failure.getCode(), failure.getMessage(), failure.getFieldErrors())));
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private ServletInputStream stream;
        private BufferedReader reader;

        BodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }

        @Override public ServletInputStream getInputStream() {
            if (reader != null) throw new IllegalStateException("getReader() has already been called");
            if (stream == null) stream = new BodyInputStream(body);
            return stream;
        }

        @Override public BufferedReader getReader() throws IOException {
            if (stream != null) throw new IllegalStateException("getInputStream() has already been called");
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset;
                try { charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding); }
                catch (IllegalArgumentException failure) { throw new UnsupportedEncodingException("Unsupported charset"); }
                reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
            }
            return reader;
        }
    }

    private static final class BodyInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;
        BodyInputStream(byte[] body) { delegate = new ByteArrayInputStream(body); }
        @Override public int read() { return delegate.read(); }
        @Override public int read(byte[] bytes, int offset, int length) { return delegate.read(bytes, offset, length); }
        @Override public int available() { return delegate.available(); }
        @Override public boolean isFinished() { return delegate.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Asynchronous reading is not supported");
        }
    }
}
