package vn.ttcs.recruitment.application;

// Task 340 (story S4-05): the only statuses a candidate sees (the intake's 201, the lookup of task 339, the withdrawal
// of task 345), and a referrer in the referral list (task 352). ApplicationStage and its display names are internal
// and never public. Declared in the order a candidate goes through them: the ordinal is the public step.
// Exhaustive switch without default: a new stage does not compile until someone decides which status shows it.
public enum PublicApplicationStatus {
    RECEIVED("Đã nhận"), IN_REVIEW("Đang xem xét"), INTERVIEWING("Mời phỏng vấn"), COMPLETED("Kết thúc");

    private final String displayName;

    PublicApplicationStatus(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }

    public static PublicApplicationStatus of(ApplicationStage stage) {
        return switch (stage) {
            case NEW -> RECEIVED;
            case SCREENING -> IN_REVIEW;
            // OFFER stays on "Mời phỏng vấn": the public step never goes back, and an offer is no outcome yet.
            case INTERVIEW, OFFER -> INTERVIEWING;
            case HIRED, REJECTED, WITHDRAWN -> COMPLETED;
        };
    }
}
