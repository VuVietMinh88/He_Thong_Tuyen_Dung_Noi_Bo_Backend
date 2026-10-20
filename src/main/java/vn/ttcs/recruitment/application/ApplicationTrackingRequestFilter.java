package vn.ttcs.recruitment.application;

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
import vn.ttcs.recruitment.common.ApiError;
import vn.ttcs.recruitment.common.ApiException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

/**
 * Task 339: the guard of the public tracking POSTs, inside Spring Security after CORS and before bearer authentication
 * and body parsing (like ApplicationIntakeFilter). In order: no-store; a query string is refused (400, the secret must
 * never be in a URL); a body that is not JSON (415) or announces more than 8 KiB (413); the whole body is read into
 * memory, at most 8 KiB (a longer chunked body or a broken one: 400 INVALID_JSON, the house handler's body); then a
 * process-local gate of app.application-tracking.max-concurrent permits (1..16, default 4), taken before any database
 * work and held until the response is complete, so the anonymous tracking API never holds more pool connections than
 * that. The body is read before the permit is taken, so a client that sends its body slowly ties up only its own
 * connection, never a permit other candidates need. A request beyond the gate gets 503 APPLICATION_TRACKING_BUSY with
 * Retry-After: 1 and writes nothing.
 */
@Component
public class ApplicationTrackingRequestFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 8192;
    private final ObjectMapper json;
    private final Semaphore permits;

    public ApplicationTrackingRequestFilter(ObjectMapper json,
            @Value("${app.application-tracking.max-concurrent:4}") int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 16) {
            throw new IllegalArgumentException("Application tracking concurrency must be 1..16");
        }
        this.json = json;
        this.permits = new Semaphore(maxConcurrent);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApplicationTrackingRoutes.isPublicPost(request);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        byte[] body;
        try {
            if (request.getQueryString() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        "Không gửi mã tra cứu hoặc email trên đường dẫn.");
            }
            MediaType type;
            try { type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType()); }
            catch (IllegalArgumentException exception) { type = MediaType.APPLICATION_OCTET_STREAM; }
            if (!"application".equals(type.getType()) || !"json".equals(type.getSubtype())) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "APPLICATION_TRACKING_JSON_REQUIRED",
                        "Vui lòng gửi dữ liệu dạng JSON.");
            }
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "APPLICATION_TRACKING_REQUEST_TOO_LARGE",
                        "Dữ liệu gửi lên quá lớn.");
            }
            body = body(request);
        } catch (ApiException exception) {
            write(response, exception);
            return;
        } catch (RuntimeException exception) {
            // Fail closed without disclosing the internal exception.
            write(response, PostgresApplicationTrackingQuota.unavailable());
            return;
        }
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            write(response, new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_TRACKING_BUSY",
                    "Hệ thống đang bận. Vui lòng thử lại sau."));
            return;
        }
        try { chain.doFilter(new BodyRequest(request, body), response); }
        finally { permits.release(); }
    }

    // At most MAX_BODY_BYTES, also without Content-Length (chunked); never more than one byte past the limit is read,
    // so the refusal does not wait for a huge chunk. A longer body, or a stream that breaks (the client went away or
    // sent nothing until the server's read timeout), is an unreadable body: 400 INVALID_JSON with the message of
    // ApiExceptionHandler.invalidJson().
    private static byte[] body(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length <= MAX_BODY_BYTES) return body;
        } catch (IOException exception) {
            // Refused below like a body that is too long.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Nội dung yêu cầu không đúng định dạng JSON.");
    }

    private void write(HttpServletResponse response, ApiException exception) throws IOException {
        response.setStatus(exception.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(
                new ApiError(exception.getCode(), exception.getMessage(), exception.getFieldErrors())));
    }

    // The body as the controller reads it: the bytes the filter has already read, through getInputStream() or through
    // getReader() in the request's charset (UTF-8 when none is given).
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private ServletInputStream stream;
        private BufferedReader reader;

        BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

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
                catch (IllegalArgumentException exception) { throw new UnsupportedEncodingException("Unsupported charset"); }
                reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
            }
            return reader;
        }
    }

    private static final class BodyInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        BodyInputStream(byte[] body) { delegate = new ByteArrayInputStream(body); }

        @Override public int read() { return delegate.read(); }
        @Override public int read(byte[] buffer, int offset, int length) { return delegate.read(buffer, offset, length); }
        @Override public int available() { return delegate.available(); }
        @Override public boolean isFinished() { return delegate.available() == 0; }
        @Override public boolean isReady() { return true; }

        // Nothing on these routes reads asynchronously: the body is already in memory.
        @Override public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Asynchronous reading is not supported");
        }
    }
}
