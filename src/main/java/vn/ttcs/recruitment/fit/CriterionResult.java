package vn.ttcs.recruitment.fit;

import java.util.List;

/**
 * Task 413 (story S5-09): one criterion of a fit result: what was required, what the application has (null when not
 * recorded), the points earned out of the criterion's share, and for skills the matched and missing ones.
 */
public record CriterionResult(FitCriterion criterion, CriterionStatus status, String required, String actual, int points,
                              int maxPoints, List<String> matchedSkills, List<String> missingSkills) {
    public CriterionResult {
        matchedSkills = List.copyOf(matchedSkills);
        missingSkills = List.copyOf(missingSkills);
    }
}
