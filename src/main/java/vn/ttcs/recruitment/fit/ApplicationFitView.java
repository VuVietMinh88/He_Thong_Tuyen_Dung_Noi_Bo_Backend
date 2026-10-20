package vn.ttcs.recruitment.fit;

import vn.ttcs.recruitment.candidate.CandidateLabels;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 413 (story S5-09): one application's recorded qualifications (null until recorded; skills null while not
 * recorded), its position's required criteria and its fit. fit.current is false when the stored result is missing or
 * names older criteria or qualifications (after a criteria save, until FitRefreshWorker rewrites it); the answer then
 * shows the evaluation computed live from the current data, so the values shown are always current.
 */
public record ApplicationFitView(UUID applicationId, UUID positionId, QualificationsView qualifications,
                                 RequiredCriteriaView criteria, FitView fit) {

    public record QualificationsView(BigDecimal experienceYears, EducationLevel educationLevel,
                                     String educationLevelName, List<String> skills, long version, Instant recordedAt,
                                     RequiredCriteriaView.Staff recordedBy) {
        static QualificationsView of(Qualifications qualifications) {
            if (qualifications == null) return null;
            EducationLevel education = qualifications.education();
            return new QualificationsView(qualifications.experienceYears(), education,
                    education == null ? null : education.displayName(),
                    qualifications.skillsRecorded()
                            ? qualifications.skills().stream().map(CandidateLabels.Label::display).toList() : null,
                    qualifications.version(), qualifications.recordedAt(),
                    new RequiredCriteriaView.Staff(qualifications.recordedById(), qualifications.recordedByName()));
        }
    }

    public record FitView(FitStatus status, String statusName, boolean requiredMet, Integer score,
                          List<CriterionResult> criteria, Instant evaluatedAt, boolean current) {
        static FitView of(FitResult result, Instant evaluatedAt, boolean current) {
            return new FitView(result.status(), result.status().displayName(), result.requiredMet(), result.score(),
                    result.criteria(), evaluatedAt, current);
        }
    }
}
