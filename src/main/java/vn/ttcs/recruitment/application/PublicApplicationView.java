package vn.ttcs.recruitment.application;

import java.time.Instant;

/**
 * Task 339: everything a candidate learns from the lookup. postingTitle is the title the posting had when the
 * application was received (a later rename does not change it); status and statusName are the public status of task
 * 340, never the internal stage. No id, requisition, posting, contact data, source, cover letter, CV or staff.
 *
 * <p>Task 341 (story S4-05, "Không tiết lộ nhận xét nội bộ hay tên người phỏng vấn"): this record, filled only by
 * {@link PublicApplicationReader}, is the only source of what the lookup returns, and its components are a whitelist,
 * not a filtered copy of an internal row. (Outside the lookup, the intake's 201 receipt and the confirmation email of
 * task 329 are the candidate's own receipt of the submission; see docs/api/application-tracking.md.) It never carries
 * an internal note, comment, evaluation, score, interview or interviewer, a recruiter, manager or any other staff
 * member, a reason, the internal stage or its label, the candidate's own contact data, source, cover letter or CV, a
 * referral, or an internal id (applicationId, requisitionId, jobPostingId). A timeline (S5-07) may add only entries of
 * {status, occurredAt} built with {@link PublicApplicationStatus#of}, never notes, reasons or actors. Every new
 * component changes the public contract: PublicApplicationResponsesTest and PublicApplicationPrivacyIntegrationTest pin
 * it, and docs/api/application-tracking.md ("Dữ liệu công khai (task 341)") must say why the candidate may see it.
 */
public record PublicApplicationView(String postingTitle, Instant receivedAt, String status, String statusName) {

    public static PublicApplicationView of(String postingTitle, Instant receivedAt, ApplicationStage stage) {
        var status = PublicApplicationStatus.of(stage);
        return new PublicApplicationView(postingTitle, receivedAt, status.name(), status.displayName());
    }
}
