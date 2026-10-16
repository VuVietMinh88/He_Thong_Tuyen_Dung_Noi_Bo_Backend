package vn.ttcs.recruitment.application;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.common.ApiError;
import vn.ttcs.recruitment.common.ApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Component
public class ApplicationIntakeFilter extends OncePerRequestFilter {
    private final ApplicationIntakeAvailability availability;
    private final ApplicationIntakeService service;
    private final ObjectMapper json;
    private final Semaphore permits;

    public ApplicationIntakeFilter(ApplicationIntakeAvailability availability, ApplicationIntakeService service,
            ObjectMapper json, @Value("${app.application-intake.max-concurrent:2}") int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 8) throw new IllegalArgumentException("Application intake concurrency must be 1..8");
        this.availability = availability; this.service = service; this.json = json;
        this.permits = new Semaphore(maxConcurrent);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApplicationIntakeRoutes.isSubmission(request);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        boolean acquired = false;
        try {
            availability.requireReady();
            availability.quota().checkAndRecord(request);
            if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Biểu mẫu không nhận tham số query.");
            }
            UUID postingId = ApplicationMultipartDecoder.uuid(ApplicationIntakeRoutes.postingId(request), "jobPostingId");
            MediaType type;
            try { type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType()); }
            catch (IllegalArgumentException exception) { type = MediaType.APPLICATION_OCTET_STREAM; }
            if (!"multipart".equals(type.getType()) || !"form-data".equals(type.getSubtype())) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "APPLICATION_FORM_TYPE_UNSUPPORTED",
                        "Vui lòng gửi biểu mẫu multipart/form-data kèm tệp CV.");
            }
            if (request.getContentLengthLong() > ApplicationMultipartDecoder.MAX_REQUEST_BYTES) throw ApplicationMultipartDecoder.tooLarge();
            acquired = permits.tryAcquire();
            if (!acquired) {
                response.setHeader("Retry-After", "1");
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_INTAKE_BUSY",
                        "Hệ thống đang bận. Vui lòng thử lại sau.");
            }
            service.requirePosting(postingId);
        } catch (ApiException exception) {
            if (acquired) permits.release();
            write(response, exception);
            return;
        } catch (RuntimeException exception) {
            if (acquired) permits.release();
            // Fail closed if the admission dependencies fail, without disclosing their internal exception.
            write(response, ApplicationIntakeAvailability.unavailable());
            return;
        }
        try { chain.doFilter(request, response); }
        finally { permits.release(); }
    }

    private void write(HttpServletResponse response, ApiException exception) throws IOException {
        if (exception instanceof ApplicationRateLimitException limited) {
            response.setHeader("Retry-After", Long.toString(limited.getRetryAfterSeconds()));
        }
        response.setStatus(exception.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(new ApiError(exception.getCode(), exception.getMessage(), exception.getFieldErrors())));
    }

}
