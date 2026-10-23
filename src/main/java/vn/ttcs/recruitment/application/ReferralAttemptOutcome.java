package vn.ttcs.recruitment.application;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;

/**
 * Task 351 (story S4-07): what a counted referral attempt answered, kept in referral_attempts.outcome (V37, whose CHECK
 * lists exactly these names) as the audit trail of who tried which posting and what came back.
 */
enum ReferralAttemptOutcome {
    /** 201: the application, its referral and the candidate's confirmation were stored. */
    STORED,
    /** 404: the posting is unknown or not open (any more). */
    POSTING_UNAVAILABLE,
    /** 409: this email already applied to the posting's requisition (the shared rule of task 327). */
    DUPLICATE,
    /** 400, 401 or 403 after the account lock: a self-referral, a lost session or a removed permission. */
    REFUSED,
    /** 503: anything else, timeouts included. */
    FAILED;

    // The outcome of the referral writer's failure. Exhaustive on what the writer throws: every other ApiException
    // status, and every other exception, is a failure of the system, not an answer about the posting or the caller.
    static ReferralAttemptOutcome of(RuntimeException failure) {
        if (failure instanceof AccessDeniedException || failure instanceof AuthenticationFailureException) {
            return REFUSED;
        }
        if (failure instanceof ApiException api) {
            HttpStatus status = api.getStatus();
            if (status == HttpStatus.NOT_FOUND) return POSTING_UNAVAILABLE;
            if (status == HttpStatus.CONFLICT) return DUPLICATE;
            if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                return REFUSED;
            }
        }
        return FAILED;
    }
}
