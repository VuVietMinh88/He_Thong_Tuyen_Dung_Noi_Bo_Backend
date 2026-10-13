package vn.ttcs.recruitment.requisition;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.approval.ApprovalChain;
import vn.ttcs.recruitment.approval.ApprovalFlowRepository;
import vn.ttcs.recruitment.common.ApiException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 256: the approval levels for a requisition of a department and a proposed salary (S3-01). The flow is the one
 * of exactly that department (no parent flow, no company default); the version is its current sealed one; a salary
 * above a level's threshold adds that level (ApprovalChain). Every selected level must be decidable by someone, and
 * since one person decides at most one level of a round, by a different person each; otherwise the requisition could
 * never finish, so submitting it is refused. That check reads unlocked accounts: the decision checks again.
 * A configuration with no level for some salary cannot be saved at all: level 1 has no threshold (255, V18).
 */
@Component
public class ApprovalChainSelector {
    private final ApprovalFlowRepository flows;
    private final ApproverDirectory directory;

    public ApprovalChainSelector(ApprovalFlowRepository flows, ApproverDirectory directory) {
        this.flows = flows;
        this.directory = directory;
    }

    // salaryBasis: proposed_salary_max, whole VND per person per month.
    public SelectedChain select(UUID departmentId, long salaryBasis, UUID createdBy, UUID submittedBy) {
        UUID flowId = flows.forDepartment(departmentId).orElseThrow(ApprovalChainSelector::notConfigured);
        var flow = flows.find(flowId, null).orElseThrow(ApprovalChainSelector::notConfigured);
        int count = ApprovalChain.levelCount(flow.steps(), BigDecimal.valueOf(salaryBasis));
        List<ApprovalLevel> levels = flow.steps().subList(0, count).stream().map(ApprovalLevel::of).toList();
        var context = new ApprovalContext(departmentId, createdBy, submittedBy, ApprovalContext.namedUsers(levels),
                Set.of(), directory.companyWideRoles());
        List<List<UUID>> admitted = levels.stream().map(level -> directory.admitted(level, context).stream()
                .map(ApproverCandidate::id).toList()).toList();
        List<ApprovalLevel> undecidable = new ArrayList<>();
        for (int index = 0; index < levels.size(); index++) {
            if (admitted.get(index).isEmpty()) {
                undecidable.add(levels.get(index));
            }
        }
        if (!undecidable.isEmpty()) {
            throw unavailable(undecidable, "hiện không có người duyệt hợp lệ");
        }
        if (!hasDistinctApprovers(admitted)) {
            throw unavailable(levels, "cần những người duyệt khác nhau nhưng không đủ người phù hợp");
        }
        return new SelectedChain(flowId, flow.version(), flow.name(), levels);
    }

    // Can every level get its own person? A bipartite matching (augmenting paths) over at most 20 levels.
    static boolean hasDistinctApprovers(List<List<UUID>> admittedPerLevel) {
        Map<UUID, Integer> levelOfPerson = new HashMap<>();
        for (int level = 0; level < admittedPerLevel.size(); level++) {
            if (!assign(level, admittedPerLevel, levelOfPerson, new HashSet<>())) {
                return false;
            }
        }
        return true;
    }

    private static boolean assign(int level, List<List<UUID>> admittedPerLevel, Map<UUID, Integer> levelOfPerson,
                                  Set<UUID> visited) {
        for (UUID person : admittedPerLevel.get(level)) {
            if (visited.add(person)) {
                Integer taken = levelOfPerson.get(person);
                if (taken == null || assign(taken, admittedPerLevel, levelOfPerson, visited)) {
                    levelOfPerson.put(person, level);
                    return true;
                }
            }
        }
        return false;
    }

    private ApiException unavailable(List<ApprovalLevel> levels, String problem) {
        String names = levels.stream().map(level -> "cấp " + level.position() + " (" + directory.approverLabel(level) + ")")
                .collect(Collectors.joining(", "));
        return new ApiException(HttpStatus.CONFLICT, "APPROVAL_LEVEL_UNAVAILABLE", "Không gửi duyệt được: " + names
                + " " + problem + ". Người tạo và người gửi yêu cầu không tự duyệt, và mỗi người chỉ duyệt một cấp"
                + " trong một lượt duyệt. Vui lòng liên hệ Trưởng phòng Nhân sự.");
    }

    private static ApiException notConfigured() {
        return new ApiException(HttpStatus.CONFLICT, "APPROVAL_FLOW_NOT_CONFIGURED",
                "Phòng ban của yêu cầu chưa có luồng phê duyệt. Vui lòng liên hệ Trưởng phòng Nhân sự để cấu hình"
                        + " trước khi gửi duyệt.");
    }
}
