package vn.ttcs.recruitment.reporting;

/**
 * Stories S8-07 and S8-09: how far an application got in the pipeline, from its history, shared by the funnel and
 * the source report so the two can never count differently.
 * <p>
 * The pipeline order is NEW (1) &lt; SCREENING (2) &lt; INTERVIEW (3) &lt; OFFER (4) &lt; HIRED (5) &lt; ONBOARDED (6);
 * REJECTED and WITHDRAWN are outcomes, not positions (0). The furthest stage an application reached is the highest
 * rank among its current stage and both sides of every stage event (application_stage_events, immutable), and never
 * less than NEW: every application was received. A later rejection, withdrawal, backward move or reopening never
 * lowers it, and an application is counted once whatever the number of events. An application that skipped a stage
 * (SCREENING straight to OFFER by an HR manager, flagged interview_skipped) has reached the stages it skipped.
 * <p>
 * COHORT_REACH is a CTE body over the alias c (cohort: id, stage), giving id, stage, max_rank and skipped.
 */
final class ApplicationReach {
    private ApplicationReach() {
    }

    static String rank(String column) {
        return "CASE " + column + " WHEN 'NEW' THEN 1 WHEN 'SCREENING' THEN 2 WHEN 'INTERVIEW' THEN 3"
                + " WHEN 'OFFER' THEN 4 WHEN 'HIRED' THEN 5 WHEN 'ONBOARDED' THEN 6 ELSE 0 END";
    }

    static final String COHORT_REACH = """
            SELECT c.id, c.stage,
                   GREATEST(1, %s, COALESCE(max(GREATEST(%s, %s)), 0)) AS max_rank,
                   COALESCE(bool_or(e.interview_skipped), FALSE) AS skipped
            FROM cohort c
            LEFT JOIN application_stage_events e ON e.application_id = c.id
            GROUP BY c.id, c.stage
            """.formatted(rank("c.stage"), rank("e.stage_before"), rank("e.stage_after"));
}
