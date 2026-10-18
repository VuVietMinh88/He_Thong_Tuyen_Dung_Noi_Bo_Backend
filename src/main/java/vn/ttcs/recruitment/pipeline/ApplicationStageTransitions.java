package vn.ttcs.recruitment.pipeline;

import vn.ttcs.recruitment.application.ApplicationStage;

/**
 * Task 380 (story S5-03, "Chuyển giai đoạn ứng viên"): the stages a staff member may move an application to from the
 * stage it is in. The one rule set of the stage API; the board's drag and drop (task 375) must use the same API and
 * may show its targets with this class, so the two can never disagree.
 * <p>
 * Forward, one step at a time, in the order of the story: NEW → SCREENING → INTERVIEW → OFFER → HIRED. Never back
 * (NEW is never a target), never out of HIRED, REJECTED or WITHDRAWN (final in this version: nothing reopens), and
 * never to WITHDRAWN: only the candidate withdraws (task 345). The requisition's status plays no part: candidates left
 * in the pipeline of a filled or cancelled requisition (task 291) must still be moved on.
 * <p>
 * Task 381 ("Chuyển sang Loại bắt buộc chọn lý do từ danh mục; không cho phép bỏ qua giai đoạn Phỏng vấn khi đi thẳng
 * tới Đề xuất offer, trừ khi Trưởng phòng Nhân sự xác nhận"): REJECTED from every active stage (the service then
 * requires an active REJECTION_REASON value), and SCREENING straight to OFFER only for an HR_MANAGER (the role itself,
 * not a permission: ADMIN or CANDIDATES_WRITE_ALL alone is not enough) who confirms it. No other stage may be skipped,
 * not even by HR (NEW to OFFER also skips SCREENING).
 */
public final class ApplicationStageTransitions {
    /** What the rules say of one move; only ALLOWED and INTERVIEW_SKIPPED change the stage. */
    public enum Decision {
        ALLOWED,
        // SCREENING to OFFER by a confirming HR_MANAGER: recorded as the HR exception.
        INTERVIEW_SKIPPED,
        // SCREENING to OFFER by anyone but an HR_MANAGER.
        INTERVIEW_REQUIRED,
        // SCREENING to OFFER by an HR_MANAGER who did not confirm it.
        INTERVIEW_SKIP_UNCONFIRMED,
        NOT_ALLOWED
    }

    private ApplicationStageTransitions() {
    }

    public static Decision decide(ApplicationStage from, ApplicationStage to, boolean hrManager,
                                  boolean confirmsInterviewSkip) {
        if (to == next(from) || (to == ApplicationStage.REJECTED && from.active())) {
            return Decision.ALLOWED;
        }
        if (from == ApplicationStage.SCREENING && to == ApplicationStage.OFFER) {
            if (!hrManager) return Decision.INTERVIEW_REQUIRED;
            return confirmsInterviewSkip ? Decision.INTERVIEW_SKIPPED : Decision.INTERVIEW_SKIP_UNCONFIRMED;
        }
        return Decision.NOT_ALLOWED;
    }

    // The next stage on the forward path, or null for a final stage. Exhaustive without default: a new stage does not
    // compile until someone decides where it leads.
    public static ApplicationStage next(ApplicationStage stage) {
        return switch (stage) {
            case NEW -> ApplicationStage.SCREENING;
            case SCREENING -> ApplicationStage.INTERVIEW;
            case INTERVIEW -> ApplicationStage.OFFER;
            case OFFER -> ApplicationStage.HIRED;
            case HIRED, REJECTED, WITHDRAWN -> null;
        };
    }
}
