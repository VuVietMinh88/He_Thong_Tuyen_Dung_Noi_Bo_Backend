package vn.ttcs.recruitment.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tasks 580-581 (story S8-09): what each candidate source brought in a cohort (the applications received between
 * from and to): how many applications, how many passed screening and how many were hired, from the stage history, with
 * the rates and the quality ranking. bestSource is the source ranked first (null when none qualifies);
 * mostApplications the real source with the most applications, shown for contrast: the largest is not always the best.
 * sources lists the ranked sources by rank, then the others by applications.
 */
public record SourceReportView(Instant generatedAt, LocalDate from, LocalDate to, String scope, ReportFilters filters,
                               int minApplications, String rankingRule, long cohortSize, Source bestSource,
                               Source mostApplications, List<Source> sources) {

    /**
     * One effective source: kind CATALOG, a source of the catalog (CANDIDATE_SOURCE; sourceKey is its id); kind
     * INTERNAL_REFERRAL, the applications a staff member referred ("Giới thiệu nội bộ", sourceKey and code
     * INTERNAL_REFERRAL, sourceId and active null); kind UNKNOWN, the applications without either (sourceKey UNKNOWN,
     * sourceId, code and active null). applications: received in the cohort; passedScreening: reached INTERVIEW or later;
     * hired: reached HIRED (ONBOARDED included); rejected, withdrawn: ended so; inProgress: still NEW..OFFER.
     * Task 581: screeningPassRate = passedScreening / applications and hireRate = hired / applications (4 decimals,
     * null without an application); qualityRank 1.. for the ranked sources (SourceRanking), null for the unknown bucket
     * and for a source below minApplications (meetsMinimumSample false).
     */
    public record Source(UUID sourceId, String sourceKey, String kind, String code, String name, Boolean active,
                         long applications,
                         long passedScreening, long hired, long rejected, long withdrawn, long inProgress,
                         BigDecimal screeningPassRate, BigDecimal hireRate, boolean meetsMinimumSample,
                         Integer qualityRank) { }
}
