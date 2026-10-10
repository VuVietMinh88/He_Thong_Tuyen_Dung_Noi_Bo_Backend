package vn.ttcs.recruitment.requisition;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 283: the recruiters currently assigned to one requisition and the rules for changing them. No I/O: the
 * repository loads it while the requisition row is locked and applies the change it returns.
 * <p>
 * A requisition is either unassigned, or has exactly one primary recruiter and 0 to 10 supporting recruiters
 * (story S3-06: "một recruiter chính và nhiều recruiter hỗ trợ"). The primary can only be replaced (a handover), never
 * removed: the old primary then leaves the requisition, and HR may add them back as supporting.
 */
public record RequisitionAssignment(UUID requisitionId, RequisitionRecruiter primary,
                                    List<RequisitionRecruiter> supporting) {
    // Only the API keeps this limit, like the 999 people of one requisition: it is a guard against mistakes, not a
    // business number from the backlog.
    public static final int MAX_SUPPORTING_RECRUITERS = 10;

    public RequisitionAssignment {
        supporting = List.copyOf(supporting);
    }

    // Builds the assignment from the stored rows. Two primaries, or supporting recruiters without a primary, can only
    // come from a writer that skipped the rules, so they fail loudly instead of being shown.
    public static RequisitionAssignment of(UUID requisitionId, List<RequisitionRecruiter> rows) {
        RequisitionRecruiter primary = null;
        List<RequisitionRecruiter> supporting = new ArrayList<>();
        for (RequisitionRecruiter row : rows) {
            if (row.role() == RequisitionRecruiterRole.SUPPORTING) {
                supporting.add(row);
            } else if (primary == null) {
                primary = row;
            } else {
                throw new IllegalStateException("Requisition " + requisitionId + " has two primary recruiters");
            }
        }
        if (primary == null && !supporting.isEmpty()) {
            throw new IllegalStateException("Requisition " + requisitionId + " has supporting recruiters only");
        }
        return new RequisitionAssignment(requisitionId, primary, supporting);
    }

    public boolean holds(UUID recruiterId, RequisitionRecruiterRole role) {
        return role == RequisitionRecruiterRole.PRIMARY
                ? primary != null && primary.recruiterId().equals(recruiterId)
                : supporting.stream().anyMatch(row -> row.recruiterId().equals(recruiterId));
    }

    /**
     * The change that gives recruiterId this role. The caller has already ruled out holds(recruiterId, role).
     * PRIMARY: the first primary, or a handover from the current one (also when recruiterId was supporting: they are
     * promoted and leave the supporting list). SUPPORTING: needs a primary, never the primary itself, at most 10.
     */
    public RecruiterChange assign(UUID recruiterId, RequisitionRecruiterRole role) {
        if (role == RequisitionRecruiterRole.PRIMARY) {
            return primary == null
                    ? new RecruiterChange(RecruiterChangeType.PRIMARY_ASSIGNED, recruiterId, null)
                    : new RecruiterChange(RecruiterChangeType.PRIMARY_HANDED_OVER, recruiterId, primary.recruiterId());
        }
        if (primary == null) {
            throw conflict("REQUISITION_PRIMARY_RECRUITER_REQUIRED", "Yêu cầu tuyển dụng chưa có recruiter chính. "
                    + "Hãy phân công recruiter chính trước khi thêm recruiter hỗ trợ.");
        }
        if (primary.recruiterId().equals(recruiterId)) {
            throw conflict("REQUISITION_RECRUITER_ALREADY_PRIMARY",
                    "Người này đang là recruiter chính của yêu cầu tuyển dụng.");
        }
        if (supporting.size() >= MAX_SUPPORTING_RECRUITERS) {
            throw conflict("REQUISITION_SUPPORTING_RECRUITER_LIMIT",
                    "Mỗi yêu cầu tuyển dụng có tối đa " + MAX_SUPPORTING_RECRUITERS + " recruiter hỗ trợ.");
        }
        return new RecruiterChange(RecruiterChangeType.SUPPORTING_ADDED, recruiterId, null);
    }

    // The change that removes recruiterId; empty when they are not assigned (nothing to do). The primary is never
    // removed, only replaced, so a requisition with recruiters always keeps one in charge.
    public Optional<RecruiterChange> unassign(UUID recruiterId) {
        if (primary != null && primary.recruiterId().equals(recruiterId)) {
            throw conflict("REQUISITION_PRIMARY_RECRUITER_REQUIRED",
                    "Không thể bỏ recruiter chính. Hãy chuyển giao cho recruiter khác.");
        }
        if (!holds(recruiterId, RequisitionRecruiterRole.SUPPORTING)) {
            return Optional.empty();
        }
        return Optional.of(new RecruiterChange(RecruiterChangeType.SUPPORTING_REMOVED, recruiterId, null));
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
