package vn.ttcs.recruitment.fit;

import vn.ttcs.recruitment.candidate.CandidateLabels;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task 413 (story S5-09, "Hồ sơ mới được gắn điểm phù hợp và cờ Đạt hoặc Không đạt tiêu chí bắt buộc; điểm chỉ để gợi ý
 * sắp xếp, không tự động loại ứng viên"): a pure function from a position's required criteria and an application's
 * recorded qualifications to a result per criterion and a bounded score. It never reads a CV and never guesses: a value
 * that was not recorded makes its criterion UNKNOWN, which earns no points and is not "met".
 * <p>
 * Every configured criterion weighs the same share of 100 (the remainder goes to the first ones, so the shares always
 * add up to 100). Experience and education earn their whole share when met; skills earn the share of required skills
 * the application has. The application meets the required criteria (requiredMet) only when every configured criterion
 * is MET. The result is advice for sorting: nothing reads it to change a stage or reject anyone.
 */
final class FitEvaluator {
    static final int MAX_SCORE = 100;

    private FitEvaluator() { }

    static FitResult evaluate(RequiredCriteria criteria, Qualifications qualifications) {
        if (criteria == null || !criteria.configured()) {
            return new FitResult(FitStatus.NO_CRITERIA, false, null, List.of());
        }
        List<FitCriterion> configured = new ArrayList<>();
        if (criteria.minExperienceYears() != null) configured.add(FitCriterion.EXPERIENCE);
        if (criteria.education() != null) configured.add(FitCriterion.EDUCATION);
        if (!criteria.skills().isEmpty()) configured.add(FitCriterion.SKILLS);
        List<CriterionResult> results = new ArrayList<>();
        for (int index = 0; index < configured.size(); index++) {
            int share = MAX_SCORE / configured.size() + (index < MAX_SCORE % configured.size() ? 1 : 0);
            results.add(switch (configured.get(index)) {
                case EXPERIENCE -> experience(criteria.minExperienceYears(), qualifications, share);
                case EDUCATION -> education(criteria.education(), qualifications, share);
                case SKILLS -> skills(criteria.skills(), qualifications, share);
            });
        }
        int score = results.stream().mapToInt(CriterionResult::points).sum();
        boolean met = results.stream().allMatch(result -> result.status() == CriterionStatus.MET);
        FitStatus status = met ? FitStatus.MET
                : results.stream().anyMatch(result -> result.status() == CriterionStatus.NOT_MET) ? FitStatus.NOT_MET
                : FitStatus.UNKNOWN;
        return new FitResult(status, met, score, List.copyOf(results));
    }

    private static CriterionResult experience(int required, Qualifications qualifications, int share) {
        BigDecimal actual = qualifications == null ? null : qualifications.experienceYears();
        if (actual == null) {
            return new CriterionResult(FitCriterion.EXPERIENCE, CriterionStatus.UNKNOWN, Integer.toString(required),
                    null, 0, share, List.of(), List.of());
        }
        boolean met = actual.compareTo(BigDecimal.valueOf(required)) >= 0;
        return new CriterionResult(FitCriterion.EXPERIENCE, met ? CriterionStatus.MET : CriterionStatus.NOT_MET,
                Integer.toString(required), actual.stripTrailingZeros().toPlainString(), met ? share : 0, share,
                List.of(), List.of());
    }

    private static CriterionResult education(EducationLevel required, Qualifications qualifications, int share) {
        EducationLevel actual = qualifications == null ? null : qualifications.education();
        if (actual == null) {
            return new CriterionResult(FitCriterion.EDUCATION, CriterionStatus.UNKNOWN, required.name(), null, 0, share,
                    List.of(), List.of());
        }
        boolean met = actual.meets(required);
        return new CriterionResult(FitCriterion.EDUCATION, met ? CriterionStatus.MET : CriterionStatus.NOT_MET,
                required.name(), actual.name(), met ? share : 0, share, List.of(), List.of());
    }

    // Skills are known only when they were recorded, even as an empty list (then every skill is missing); skills that
    // were never recorded are UNKNOWN.
    private static CriterionResult skills(List<CandidateLabels.Label> required, Qualifications qualifications, int share) {
        List<String> names = required.stream().map(CandidateLabels.Label::display).toList();
        String requiredText = String.join(", ", names);
        if (qualifications == null || !qualifications.skillsRecorded()) {
            return new CriterionResult(FitCriterion.SKILLS, CriterionStatus.UNKNOWN, requiredText, null, 0, share,
                    List.of(), names);
        }
        Set<String> held = qualifications.skills().stream().map(CandidateLabels.Label::key).collect(Collectors.toSet());
        List<String> matched = required.stream().filter(skill -> held.contains(skill.key()))
                .map(CandidateLabels.Label::display).toList();
        List<String> missing = required.stream().filter(skill -> !held.contains(skill.key()))
                .map(CandidateLabels.Label::display).toList();
        int points = (int) Math.round((double) share * matched.size() / required.size());
        return new CriterionResult(FitCriterion.SKILLS, missing.isEmpty() ? CriterionStatus.MET : CriterionStatus.NOT_MET,
                requiredText, String.join(", ", qualifications.skills().stream().map(CandidateLabels.Label::display)
                .toList()), points, share, matched, missing);
    }
}
