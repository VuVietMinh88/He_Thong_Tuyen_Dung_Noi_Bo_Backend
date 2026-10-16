package vn.ttcs.recruitment.requisition;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

// Task 284: one assigned recruiter as the API shows it: id and name (never the email), who gave them this role and
// when. eligible says whether they could be given a role today (RecruiterEligibility); it is only shown to the HR
// users who manage the team (REQUISITIONS_WRITE_ALL), and NON_NULL leaves the key out for everyone else.
public record AssignedRecruiterView(UUID recruiterId, String fullName,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean eligible,
                                    UUID assignedById, String assignedBy, Instant assignedAt) {
}
