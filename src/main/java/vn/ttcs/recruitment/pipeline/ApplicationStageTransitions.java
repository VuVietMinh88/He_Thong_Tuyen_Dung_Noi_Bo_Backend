package vn.ttcs.recruitment.pipeline;

import vn.ttcs.recruitment.application.ApplicationStage;

/**
 * Task 380 (story S5-03, "Chuyển giai đoạn ứng viên"): the stages a staff member may move an application to from the
 * stage it is in. The one rule set of the stage API; the board's drag and drop (task 375) asks the same class, so the
 * two can never disagree.
 * <p>
 * Forward, one step at a time, in the order of the story: NEW → SCREENING → INTERVIEW → OFFER → HIRED. Never back
 * (NEW is never a target), never out of HIRED, REJECTED or WITHDRAWN (final in this version: nothing reopens), and
 * never to WITHDRAWN: only the candidate withdraws (task 345). The requisition's status plays no part: candidates left
 * in the pipeline of a filled or cancelled requisition (task 291) must still be moved on.
 */
final class ApplicationStageTransitions {
    private ApplicationStageTransitions() {
    }

    static boolean allowed(ApplicationStage from, ApplicationStage to) {
        return to == next(from);
    }

    // The next stage on the forward path, or null for a final stage. Exhaustive without default: a new stage does not
    // compile until someone decides where it leads.
    static ApplicationStage next(ApplicationStage stage) {
        return switch (stage) {
            case NEW -> ApplicationStage.SCREENING;
            case SCREENING -> ApplicationStage.INTERVIEW;
            case INTERVIEW -> ApplicationStage.OFFER;
            case OFFER -> ApplicationStage.HIRED;
            case HIRED, REJECTED, WITHDRAWN -> null;
        };
    }
}
