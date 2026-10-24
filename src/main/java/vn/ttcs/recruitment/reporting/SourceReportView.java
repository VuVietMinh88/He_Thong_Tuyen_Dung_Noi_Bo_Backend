package vn.ttcs.recruitment.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Task 580 (story S8-09): what each candidate source brought in a cohort (the applications received between from and
 * to): how many applications, how many passed screening and how many were hired, from the stage history.
 */
public record SourceReportView(Instant generatedAt, LocalDate from, LocalDate to, String scope, long cohortSize,
                               List<Source> sources) {

    /**
     * One effective source: kind CATALOG, a source of the catalog (CANDIDATE_SOURCE; sourceKey is its id); kind
     * INTERNAL_REFERRAL, the applications a staff member referred ("Giới thiệu nội bộ", sourceKey and code
     * INTERNAL_REFERRAL, sourceId and active null); kind UNKNOWN, the applications without either (sourceKey UNKNOWN,
     * sourceId, code and active null). applications: received in the cohort; passedScreening: reached INTERVIEW or later;
     * hired: reached HIRED (ONBOARDED included); rejected, withdrawn: ended so; inProgress: still NEW..OFFER.
     */
    public record Source(UUID sourceId, String sourceKey, String kind, String code, String name, Boolean active,
                         long applications, long passedScreening, long hired, long rejected, long withdrawn,
                         long inProgress) { }
}
