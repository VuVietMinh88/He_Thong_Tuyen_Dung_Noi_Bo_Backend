package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.RetryAfterApiException;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Task 351 (story S4-07, "Nhân viên nội bộ nộp hồ sơ giúp người quen kèm ghi chú giới thiệu"): a logged-in staff member
 * refers a candidate to an open posting with the candidate's CV, a note and the declaration that the candidate agreed.
 * Not transactional: the slow work (reading up to 11 MiB, checking the CV) is done before any lock. Order of checks:
 * <ol>
 * <li>the matcher's PERM_SELF_PROFILE_WRITE (401/403, SecurityConfiguration);</li>
 * <li>Content-Type multipart/form-data, else 415 APPLICATION_FORM_TYPE_UNSUPPORTED, before readiness (so the JSON
 * sample of the authorization matrix gets 415 whatever the configuration);</li>
 * <li>Content-Length above 11 MiB: 413 APPLICATION_CV_TOO_LARGE;</li>
 * <li>the confirmation key (the candidate must get the tracking code by email): 503 REFERRAL_UNAVAILABLE. Referrals
 * do not depend on app.application-intake.enabled, which guards the anonymous route;</li>
 * <li>at most app.referral.max-concurrent referrals (1..8, default 2) read at once per process: 503 REFERRAL_BUSY with
 * Retry-After: 1; the permit is held to the end and released in finally;</li>
 * <li>the form (ApplicationMultipartDecoder.decodeReferral) and its fields: 400;</li>
 * <li>the CV (CvValidator, active content refused since task 362): 400/413/415;</li>
 * <li>the attempt quota (ReferralAttemptQuota, its own committed transaction): 429 with Retry-After;</li>
 * <li>the write (ApplicationReferralWriter): 401/403 after the account lock, 400 self-referral, 404, 409; the attempt
 * then gets its outcome (best effort).</li>
 * </ol>
 * ApiException, AccessDeniedException and AuthenticationFailureException pass; anything else (a lock or statement
 * timeout, a failed commit) is 503 REFERRAL_SUBMISSION_UNAVAILABLE without detail. The 201 is the referral as its
 * referrer's list shows it (task 352), never the tracking code.
 */
@Service
public class ApplicationReferralService {
    private final ApplicationConfirmationSettings settings;
    private final ApplicationMultipartDecoder decoder;
    private final CvValidator cvs;
    private final ReferralAttemptQuota attempts;
    private final ApplicationReferralWriter writer;
    private final Semaphore permits;

    ApplicationReferralService(ApplicationConfirmationSettings settings, ApplicationMultipartDecoder decoder,
            CvValidator cvs, ReferralAttemptQuota attempts, ApplicationReferralWriter writer,
            @Value("${app.referral.max-concurrent:2}") int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 8) throw new IllegalArgumentException("Referral concurrency must be 1..8");
        this.settings = settings; this.decoder = decoder; this.cvs = cvs; this.attempts = attempts; this.writer = writer;
        this.permits = new Semaphore(maxConcurrent);
    }

    public ApplicationReferralView submit(Jwt jwt, HttpServletRequest request) {
        requireMultipart(request);
        if (request.getContentLengthLong() > ApplicationMultipartDecoder.MAX_REQUEST_BYTES) {
            throw ApplicationMultipartDecoder.tooLarge();
        }
        if (!settings.ready()) throw unavailable();
        if (!permits.tryAcquire()) {
            throw new RetryAfterApiException(HttpStatus.SERVICE_UNAVAILABLE, "REFERRAL_BUSY",
                    "Hệ thống đang bận. Vui lòng thử lại sau.", 1);
        }
        try {
            UUID referrerId = referrerId(jwt);
            var form = decoder.decodeReferral(request);
            var cv = cvs.validate(form.filename(), new ByteArrayInputStream(form.bytes()));
            long attempt = attempts.checkAndRecord(referrerId, form.postingId());
            ReferralAttemptOutcome outcome = ReferralAttemptOutcome.FAILED;
            try {
                var referral = Objects.requireNonNull(writer.refer(jwt, form, cv));
                outcome = ReferralAttemptOutcome.STORED;
                return referral;
            } catch (RuntimeException failure) {
                outcome = ReferralAttemptOutcome.of(failure);
                throw failure;
            } finally {
                attempts.complete(attempt, outcome);
            }
        } catch (ApiException | AccessDeniedException | AuthenticationFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // No SQL, class name, submitted value or file name reaches the referrer.
            throw submissionUnavailable();
        } finally {
            permits.release();
        }
    }

    // The referrer is the authenticated caller (the JWT's subject), never a form field. The writer checks the account,
    // the session and the permission again under their locks.
    private static UUID referrerId(Jwt jwt) {
        try {
            return UUID.fromString(Objects.requireNonNull(jwt).getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
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
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REFERRAL_UNAVAILABLE",
                "Chức năng giới thiệu ứng viên chưa sẵn sàng. Vui lòng thử lại sau.");
    }

    static ApiException submissionUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REFERRAL_SUBMISSION_UNAVAILABLE",
                "Chưa thể ghi nhận giới thiệu lúc này. Vui lòng thử lại sau.");
    }
}
