package vn.ttcs.recruitment.candidatesearch;

import java.time.Instant;
import java.util.UUID;

/** Task 394: one saved filter of the caller. The owner is always the caller, so it is not repeated. */
public record SavedFilterView(UUID id, String name, CandidateSearchCriteria criteria, Instant createdAt,
                              Instant updatedAt, long version) {
}
