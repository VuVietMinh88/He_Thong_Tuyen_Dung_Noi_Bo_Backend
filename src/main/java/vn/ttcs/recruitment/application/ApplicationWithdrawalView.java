package vn.ttcs.recruitment.application;

import java.time.Instant;

/**
 * Task 345 (story S4-06): the 200 body of a withdrawal, the first call and every repeat alike. postingTitle and
 * receivedAt are the snapshots of the application (as in the lookup, task 339); status and statusName are the public
 * status of task 340 for WITHDRAWN ("Kết thúc"); withdrawnAt is when the candidate withdrew, the occurred_at of the
 * application's WITHDRAWN stage event (V34), and null only for an application moved to WITHDRAWN without one (raw SQL
 * before task 345). A whitelist like PublicApplicationView: no id, requisition, posting, internal stage, reason,
 * contact data or staff. Every new component changes the public contract and is pinned by
 * PublicApplicationResponsesTest.
 */
public record ApplicationWithdrawalView(String postingTitle, Instant receivedAt, String status, String statusName,
                                        Instant withdrawnAt) {

    static ApplicationWithdrawalView of(String postingTitle, Instant receivedAt, Instant withdrawnAt) {
        var status = PublicApplicationStatus.of(ApplicationStage.WITHDRAWN);
        return new ApplicationWithdrawalView(postingTitle, receivedAt, status.name(), status.displayName(), withdrawnAt);
    }
}
