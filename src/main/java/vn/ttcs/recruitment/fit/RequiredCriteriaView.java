package vn.ttcs.recruitment.fit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 412 (story S5-09): the required criteria of a position as stored. configured is false (version 0, no values)
 * while nothing is required; updatedBy and updatedAt are null until the first save.
 */
public record RequiredCriteriaView(UUID positionId, boolean configured, Integer minExperienceYears,
                                   EducationLevel requiredEducation, String requiredEducationName,
                                   List<String> requiredSkills, long version, Instant updatedAt, Staff updatedBy) {
    public record Staff(UUID id, String fullName) { }

    static RequiredCriteriaView of(UUID positionId, RequiredCriteria criteria) {
        if (criteria == null) {
            return new RequiredCriteriaView(positionId, false, null, null, null, List.of(), 0, null, null);
        }
        EducationLevel education = criteria.education();
        return new RequiredCriteriaView(positionId, criteria.configured(), criteria.minExperienceYears(), education,
                education == null ? null : education.displayName(), criteria.skillNames(), criteria.version(),
                criteria.updatedAt(), new Staff(criteria.updatedById(), criteria.updatedByName()));
    }
}
