package vn.ttcs.recruitment.requisition;

import java.util.Objects;
import java.util.UUID;

// Task 283: one change of the recruiters of a requisition. recruiterId is the new primary (PRIMARY_*), or the supporting
// recruiter added or removed (SUPPORTING_*). previousRecruiterId is the primary that was replaced, only for a handover.
public record RecruiterChange(RecruiterChangeType type, UUID recruiterId, UUID previousRecruiterId) {

    public RecruiterChange {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(recruiterId, "recruiterId");
        if ((type == RecruiterChangeType.PRIMARY_HANDED_OVER) != (previousRecruiterId != null)) {
            throw new IllegalArgumentException("Only a handover names the previous primary recruiter");
        }
    }
}
