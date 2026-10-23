package vn.ttcs.recruitment.fit;

import java.util.List;

/**
 * Task 413 (story S5-09): the fit of one application: the overall status, whether every required criterion is met
 * (the "Đạt/Không đạt" flag), the score 0..100 (null when the position requires nothing) and one result per criterion.
 */
public record FitResult(FitStatus status, boolean requiredMet, Integer score, List<CriterionResult> criteria) {
    public FitResult {
        criteria = List.copyOf(criteria);
        if (requiredMet != (status == FitStatus.MET)) throw new IllegalArgumentException("requiredMet must match MET");
        if ((status == FitStatus.NO_CRITERIA) != (score == null)) {
            throw new IllegalArgumentException("Only a position without criteria has no score");
        }
        if (score != null && (score < 0 || score > FitEvaluator.MAX_SCORE)) {
            throw new IllegalArgumentException("A score is 0..100");
        }
    }
}
