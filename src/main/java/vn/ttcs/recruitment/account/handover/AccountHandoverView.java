package vn.ttcs.recruitment.account.handover;

import vn.ttcs.recruitment.account.AccountStatus;
import vn.ttcs.recruitment.approval.ApprovalFlowRepository;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RequisitionHandover;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/v1/accounts/{id}/handover-items: the work the account still holds. The four lists are always present,
 * possibly empty, so the lock dialog can tell "nothing to hand over" from a missing answer.
 */
public record AccountHandoverView(UUID userId, String fullName, AccountStatus status,
                                  List<HandoverRequisition> requisitions,
                                  List<HandoverDepartment> managedDepartments,
                                  List<HandoverApprovalStep> approvalSteps,
                                  List<HandoverPendingApproval> pendingApprovals) {
    public AccountHandoverView {
        requisitions = List.copyOf(requisitions);
        managedDepartments = List.copyOf(managedDepartments);
        approvalSteps = List.copyOf(approvalSteps);
        pendingApprovals = List.copyOf(pendingApprovals);
    }

    // A requisition whose recruiter team has the account (role PRIMARY or SUPPORTING) and whose recruiters may still
    // change.
    public record HandoverRequisition(UUID requisitionId, String positionCode, String positionName, UUID departmentId,
                                      String departmentName, RequisitionStatus status, RequisitionRecruiterRole role,
                                      int headcount, LocalDate neededBy, Instant assignedAt) {
        static HandoverRequisition of(RequisitionHandover.HeldRequisition row) {
            return new HandoverRequisition(row.requisitionId(), row.positionCode(), row.positionName(),
                    row.departmentId(), row.departmentName(), row.status(), row.role(), row.headcount(),
                    row.neededBy(), row.assignedAt());
        }
    }

    // A department the account manages directly: an active one, or an inactive one that still has open
    // requisitions.
    public record HandoverDepartment(UUID departmentId, String code, String name, boolean active) {
        static HandoverDepartment of(DepartmentRepository.ManagedDepartment row) {
            return new HandoverDepartment(row.departmentId(), row.code(), row.name(), row.active());
        }
    }

    // A step of the current approval-flow version of an active department that names the account. Versions are
    // sealed, so the dialog only warns: saving a new version of the flow changes the approver of later submissions
    // only, not of the rounds already waiting (pendingApprovals).
    public record HandoverApprovalStep(UUID flowId, UUID departmentId, String departmentName, String flowName,
                                       int version, int position) {
        static HandoverApprovalStep of(ApprovalFlowRepository.NamedApprovalStep row) {
            return new HandoverApprovalStep(row.flowId(), row.departmentId(), row.departmentName(), row.flowName(),
                    row.version(), row.position());
        }
    }

    // A level of an approval round in progress that names the account and is not decided yet. The round keeps the
    // flow version it was submitted with, so a new version of the flow does not change it, and nobody may decide a
    // level that names another account: the dialog only warns.
    public record HandoverPendingApproval(UUID requisitionId, String positionCode, String positionName,
                                          UUID departmentId, String departmentName, int round, int level,
                                          boolean current, UUID flowId, int flowVersion, Instant waitingSince) {
        static HandoverPendingApproval of(RequisitionHandover.AwaitedApproval row) {
            return new HandoverPendingApproval(row.requisitionId(), row.positionCode(), row.positionName(),
                    row.departmentId(), row.departmentName(), row.round(), row.level(), row.current(), row.flowId(),
                    row.flowVersion(), row.waitingSince());
        }
    }
}
