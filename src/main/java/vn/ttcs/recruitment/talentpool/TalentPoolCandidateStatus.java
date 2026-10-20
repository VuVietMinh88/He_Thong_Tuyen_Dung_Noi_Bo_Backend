package vn.ttcs.recruitment.talentpool;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Task 398 (story S5-06): where a pooled candidate stands now, from all of the candidate's applications: HIRED when one
 * is hired or onboarded, IN_PIPELINE when one is still being processed, AVAILABLE otherwise. The pool list shows it;
 * suggestions for a new position only offer AVAILABLE candidates (task 400).
 */
public enum TalentPoolCandidateStatus {
    AVAILABLE("Có thể liên hệ"),
    IN_PIPELINE("Đang có hồ sơ đang xử lý"),
    HIRED("Đã nhận việc");

    private final String displayName;

    TalentPoolCandidateStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }

    // What one application in this stage says about its candidate. Exhaustive: a new stage must be placed here.
    static TalentPoolCandidateStatus forStage(ApplicationStage stage) {
        return switch (stage) {
            case NEW, SCREENING, INTERVIEW, OFFER -> IN_PIPELINE;
            case HIRED, ONBOARDED -> HIRED;
            case REJECTED, WITHDRAWN -> AVAILABLE;
        };
    }

    // The stages of forStage(status) as an SQL list of literals, for example 'HIRED', 'ONBOARDED'.
    static String stagesSql(TalentPoolCandidateStatus status) {
        return Arrays.stream(ApplicationStage.values()).filter(stage -> forStage(stage) == status)
                .map(stage -> "'" + stage.name() + "'").collect(Collectors.joining(", "));
    }

    // The SQL of the status of the entry e's candidate.
    static String sql() {
        return "CASE WHEN EXISTS (SELECT 1 FROM candidate_applications sc JOIN job_applications sa ON sa.id = sc.application_id"
                + " WHERE sc.candidate_id = e.candidate_id AND sa.stage IN (" + stagesSql(HIRED) + ")) THEN 'HIRED'"
                + " WHEN EXISTS (SELECT 1 FROM candidate_applications sc JOIN job_applications sa ON sa.id = sc.application_id"
                + " WHERE sc.candidate_id = e.candidate_id AND sa.stage IN (" + stagesSql(IN_PIPELINE) + ")) THEN 'IN_PIPELINE'"
                + " ELSE 'AVAILABLE' END";
    }
}
