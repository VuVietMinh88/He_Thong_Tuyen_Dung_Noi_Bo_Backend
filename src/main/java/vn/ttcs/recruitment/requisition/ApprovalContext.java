package vn.ttcs.recruitment.requisition;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 256: what ApproverRule needs to know about the requisition and its round. departmentId is the requisition's
 * department; createdBy and submittedBy (the submitter of the round) never decide it; namedUserIds are the accounts a
 * user step of the same chain names, who may not also take one of its role steps; decidedInRound are the people who
 * already decided a level of this round (one level per person); companyWideRoles are the roles whose holders may take
 * a role step of any department.
 */
public record ApprovalContext(UUID departmentId, UUID createdBy, UUID submittedBy, Set<UUID> namedUserIds,
                              Set<UUID> decidedInRound, Set<String> companyWideRoles) {
    public ApprovalContext {
        Objects.requireNonNull(departmentId, "departmentId");
        namedUserIds = Set.copyOf(namedUserIds);
        decidedInRound = Set.copyOf(decidedInRound);
        companyWideRoles = Set.copyOf(companyWideRoles);
    }

    public static Set<UUID> namedUsers(List<ApprovalLevel> levels) {
        return levels.stream().map(ApprovalLevel::approverUserId).filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
