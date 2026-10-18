package vn.ttcs.recruitment.requisition;

import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.security.AccessScope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Task 262: who may read a requisition beyond the department scope of REQUISITIONS_READ_SCOPED: the people of its
 * approval. An approver (an APPROVER, or the head of a parent department) usually manages none of the requisition's
 * departments, yet must read what they decide. A participant is someone who decided a level of the requisition (in
 * any round, for as long as the requisition exists), or someone a level is routed to (ApproverRule.targets) that a
 * round actually reached: a level not reached yet opens nothing; a level that waited when HR cancelled the requisition
 * counts as reached (task 293, ApprovalRound.interrupted), so its approvers can still read what they were asked to
 * decide and a late decision answers 409. Targets rather than "may decide now" on purpose: two holders of a role
 * clicking the same level both read it, and the second gets a 409 it can show, not a 403.
 * Write scope is never widened: deciding is authorized by ApproverRule, not by department scope.
 * <p>
 * Task 285: GET /requisitions/{id} also admits the requisition's current recruiters, in
 * RequisitionService.requireReadable; readable() deliberately does not, because a decision uses it (an assigned
 * recruiter who may not decide must get 403, not a 409 that shows the requisition's state).
 */
@Component
public class ApprovalParticipants {
    private final RequisitionApprovalRepository approvals;
    private final ApproverDirectory directory;

    public ApprovalParticipants(RequisitionApprovalRepository approvals, ApproverDirectory directory) {
        this.approvals = approvals;
        this.directory = directory;
    }

    // May this reader read the requisition? readScope: the caller's READ scope of REQUISITIONS (ALL or SCOPED).
    // events: the requisition's approval history, loaded only when the department rule does not already answer.
    public boolean readable(ApproverCandidate reader, AccessScope readScope, RecruitmentRequisition requisition,
                            Supplier<List<ApprovalEvent>> events) {
        return byDepartment(reader, readScope, requisition) || isParticipant(reader, requisition, events.get());
    }

    // ALL, or a SCOPED reader who manages the requisition's department (with its parents, task 249). Shared with
    // RequisitionService.requireReadable, which adds the recruiter assignment (task 285) between this and
    // isParticipant.
    public static boolean byDepartment(ApproverCandidate reader, AccessScope readScope,
                                       RecruitmentRequisition requisition) {
        return readScope == AccessScope.ALL
                || reader.managedDepartmentIds().get().contains(requisition.getDepartmentId());
    }

    public boolean isParticipant(ApproverCandidate reader, RecruitmentRequisition requisition,
                                 List<ApprovalEvent> events) {
        boolean decided = events.stream().anyMatch(event -> event.level() != null
                && event.actorId().equals(reader.id()));
        if (decided) {
            return true;
        }
        Set<String> companyWideRoles = null;
        Map<String, List<ApprovalLevel>> chains = new HashMap<>();
        for (ApprovalRound round : ApprovalRound.all(events)) {
            int reached = round.reachedLevel();
            if (reached == 0) {
                continue;
            }
            if (companyWideRoles == null) {
                companyWideRoles = directory.companyWideRoles();
            }
            List<ApprovalLevel> levels = chains.computeIfAbsent(round.flowId() + "/" + round.flowVersion() + "/"
                    + round.levelCount(), key -> approvals.findChainLevels(round.flowId(), round.flowVersion(),
                    round.levelCount()).stream().map(ChainLevelRow::level).toList());
            var context = round.context(requisition, companyWideRoles, levels);
            for (ApprovalLevel level : levels.subList(0, Math.min(reached, levels.size()))) {
                if (ApproverRule.targets(level, context, reader)) {
                    return true;
                }
            }
        }
        return false;
    }
}
