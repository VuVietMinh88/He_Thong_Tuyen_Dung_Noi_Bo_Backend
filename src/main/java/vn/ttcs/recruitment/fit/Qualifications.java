package vn.ttcs.recruitment.fit;

import vn.ttcs.recruitment.candidate.CandidateLabels;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 413 (story S5-09): what a staff member recorded about one application after reading its CV: years of
 * experience (one decimal), the highest education level and the skills. A null value was not recorded, and skills
 * were recorded only when skillsRecorded (an empty recorded list means "none"); nothing is ever extracted from a CV
 * automatically.
 */
record Qualifications(BigDecimal experienceYears, EducationLevel education, List<CandidateLabels.Label> skills,
                      boolean skillsRecorded, long version, Instant recordedAt, UUID recordedById,
                      String recordedByName) {
    Qualifications {
        skills = List.copyOf(skills);
    }
}
