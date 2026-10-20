package vn.ttcs.recruitment.application;

import java.time.Instant;

/**
 * Task 339: everything a candidate learns from the lookup. postingTitle is the title the posting had when the
 * application was received (a later rename does not change it); status and statusName are the public status of task
 * 340, never the internal stage. No id, requisition, posting, contact data, source, cover letter, CV or staff.
 */
public record PublicApplicationView(String postingTitle, Instant receivedAt, String status, String statusName) {

    public static PublicApplicationView of(String postingTitle, Instant receivedAt, ApplicationStage stage) {
        var status = PublicApplicationStatus.of(stage);
        return new PublicApplicationView(postingTitle, receivedAt, status.name(), status.displayName());
    }
}
