package vn.ttcs.recruitment.fit;

import vn.ttcs.recruitment.candidate.CandidateLabels;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 412 (story S5-09): the stored required criteria of one position, version included. A null minimum or education
 * and an empty skill list mean "not required".
 */
record RequiredCriteria(UUID positionId, Integer minExperienceYears, EducationLevel education,
                        List<CandidateLabels.Label> skills, long version, Instant updatedAt, UUID updatedById,
                        String updatedByName) {
    RequiredCriteria {
        skills = List.copyOf(skills);
    }

    boolean configured() {
        return minExperienceYears != null || education != null || !skills.isEmpty();
    }

    List<String> skillNames() {
        return skills.stream().map(CandidateLabels.Label::display).toList();
    }
}
