package vn.ttcs.recruitment.candidate;

import vn.ttcs.recruitment.security.AccessScope;

import java.util.Objects;
import java.util.UUID;

/**
 * Task 363 (story S4-09): a caller allowed to read candidate data, as CandidateAccess.requireReader resolved it: the
 * account and how far its CANDIDATES read permission reaches, ALL or SCOPED (never NONE: that caller gets 403 first).
 * Ids only, so toString shows no personal data.
 */
public record CandidateReader(UUID id, AccessScope scope) {
    public CandidateReader {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        if (scope == AccessScope.NONE) {
            throw new IllegalArgumentException("A candidate reader needs CANDIDATES_READ_ALL or CANDIDATES_READ_SCOPED");
        }
    }

    // The access_scope value of candidate_access_events (V33). Exhaustive on purpose: a new AccessScope value fails to
    // compile here instead of being logged under a wrong name.
    String logScope() {
        return switch (scope) {
            case ALL -> "ALL";
            case SCOPED -> "SCOPED";
            case NONE -> throw new IllegalStateException("A candidate reader never has the scope NONE");
        };
    }
}
