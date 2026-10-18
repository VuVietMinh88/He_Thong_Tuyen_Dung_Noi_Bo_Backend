package vn.ttcs.recruitment.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 351 (story S4-07): one referral as its referrer sees it, under the names the frontend reads
 * (referralService.ts sanitizeReferralData: id, jobTitle, submittedAt; the create path: id, jobTitle, status). The
 * referral API's 201 is exactly this item, and so is every item of the referrer's own list (task 352). id is the
 * application's id (a stable React key), jobTitle the posting-title snapshot, submittedAt the receipt, status and
 * statusName the public status of task 340, referralNote the referrer's own note. Never in it: the candidate's email,
 * phone or CV, the internal stage, staff, the tracking code or link (they belong to the candidate, who gets them by
 * email). toString hides the name and the note.
 */
public record ApplicationReferralView(UUID id, String candidateName, String jobTitle, Instant submittedAt, String status,
                                      String statusName, String referralNote) {
    static ApplicationReferralView of(UUID id, String candidateName, String jobTitle, Instant submittedAt,
                                      ApplicationStage stage, String referralNote) {
        var status = PublicApplicationStatus.of(stage);
        return new ApplicationReferralView(id, candidateName, jobTitle, submittedAt, status.name(),
                status.displayName(), referralNote);
    }

    @Override public String toString() { return "ApplicationReferralView[" + id + ", personal data redacted]"; }
}
