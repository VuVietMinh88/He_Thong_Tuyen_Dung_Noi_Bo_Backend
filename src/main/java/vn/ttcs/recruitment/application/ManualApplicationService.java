package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.candidate.CandidateWriteGuard;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.RetryAfterApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Task 407 (story S5-08, "Nhập tay thông tin và tải CV cho ứng viên tìm được từ nguồn khác"): an internal user enters
 * a candidate found elsewhere into a requisition, with the candidate's CV. Not transactional: the slow work (reading up
 * to 11 MiB, checking the CV) is done before any lock, like the referral. Order of checks:
 * <ol>
 * <li>the matcher's CANDIDATES write permission (401/403, SecurityConfiguration), then the CANDIDATES read and write
 * permissions read again from the database (403), before the body is read;</li>
 * <li>Content-Type multipart/form-data, else 415 APPLICATION_FORM_TYPE_UNSUPPORTED; Content-Length above 11 MiB, 413;</li>
 * <li>the confirmation key (the candidate receives the tracking code by email, as from the public form): 503
 * MANUAL_APPLICATION_UNAVAILABLE; at most app.manual-application.max-concurrent intakes (1..8, default 2) at once per
 * process: 503 MANUAL_APPLICATION_BUSY with Retry-After;</li>
 * <li>the form (400) and the CV with the shared CvValidator (400/413/415, active content refused);</li>
 * <li>the write (ManualApplicationWriter): 401/403 after the account lock, 404/403 for the requisition, then every
 * rule of the public form through ApplicationStorageService (404/409 posting, 400 source, 409 duplicate email).</li>
 * </ol>
 * ApiException, AccessDeniedException and AuthenticationFailureException pass; anything else is 503
 * MANUAL_APPLICATION_FAILED (or MANUAL_APPLICATION_BUSY for a lock timeout) without detail. No submitted value reaches
 * a log or a message.
 */
@Service
public class ManualApplicationService {
    private final ApplicationConfirmationSettings settings;
    private final ApplicationMultipartDecoder decoder;
    private final CvValidator cvs;
    private final ManualApplicationWriter writer;
    private final CandidateAccess access;
    private final PermissionService permissions;
    private final Semaphore permits;

    ManualApplicationService(ApplicationConfirmationSettings settings, ApplicationMultipartDecoder decoder,
                             CvValidator cvs, ManualApplicationWriter writer, CandidateAccess access,
                             PermissionService permissions,
                             @Value("${app.manual-application.max-concurrent:2}") int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 8) {
            throw new IllegalArgumentException("Manual application concurrency must be 1..8");
        }
        this.settings = settings;
        this.decoder = decoder;
        this.cvs = cvs;
        this.writer = writer;
        this.access = access;
        this.permissions = permissions;
        this.permits = new Semaphore(maxConcurrent);
    }

    public ManualApplicationView submit(Jwt jwt, UUID requisitionId, HttpServletRequest request) {
        CandidateReader reader = access.requireReader(jwt);
        AccessScope.write(permissions.forUser(reader.id()), PermissionModule.CANDIDATES).orDeny();
        requireMultipart(request);
        if (request.getContentLengthLong() > ApplicationMultipartDecoder.MAX_REQUEST_BYTES) {
            throw ApplicationMultipartDecoder.tooLarge();
        }
        if (!settings.ready()) throw unavailable();
        if (!permits.tryAcquire()) {
            throw new RetryAfterApiException(HttpStatus.SERVICE_UNAVAILABLE, "MANUAL_APPLICATION_BUSY",
                    "Hệ thống đang bận. Vui lòng thử lại sau.", 1);
        }
        try {
            var form = decoder.decodeManual(request);
            var cv = cvs.validate(form.filename(), new ByteArrayInputStream(form.bytes()));
            return Objects.requireNonNull(writer.enter(jwt, requisitionId, form, cv));
        } catch (ApiException | AccessDeniedException | AuthenticationFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (CandidateWriteGuard.busy(exception)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MANUAL_APPLICATION_BUSY",
                        "Yêu cầu tuyển dụng đang được xử lý. Vui lòng thử lại.");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MANUAL_APPLICATION_FAILED",
                    "Chưa thể lưu hồ sơ lúc này. Vui lòng thử lại sau.");
        } finally {
            permits.release();
        }
    }

    private static void requireMultipart(HttpServletRequest request) {
        MediaType type;
        try { type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType()); }
        catch (IllegalArgumentException exception) { type = MediaType.APPLICATION_OCTET_STREAM; }
        if (!"multipart".equals(type.getType()) || !"form-data".equals(type.getSubtype())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "APPLICATION_FORM_TYPE_UNSUPPORTED",
                    "Vui lòng gửi biểu mẫu multipart/form-data kèm tệp CV.");
        }
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MANUAL_APPLICATION_UNAVAILABLE",
                "Chức năng thêm hồ sơ thủ công chưa sẵn sàng. Vui lòng thử lại sau.");
    }
}
