package vn.ttcs.recruitment.account.handover;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * POST /api/v1/accounts/{id}/handover: which requisitions and which managed departments of the account go to whom,
 * with one note for all of them. The client never sends a role: the server hands over the role the account holds on
 * each requisition. note has the rules of the note of POST /requisitions/{id}/assign and is stored with every change
 * in the recruiter history (V17); department manager changes have no history. A missing list is an empty one.
 */
public record AccountHandoverRequest(
        @Size(max = 1_000, message = "Ghi chú tối đa 1.000 ký tự.")
        @Pattern(regexp = NO_NUL_CHARACTER, message = "Ghi chú chứa ký tự không hợp lệ.")
        String note,
        @Size(max = MAX_ITEMS, message = "Mỗi lần bàn giao tối đa 100 yêu cầu tuyển dụng.")
        List<@NotNull(message = "Vui lòng chọn yêu cầu tuyển dụng và người nhận bàn giao.") @Valid Item> requisitions,
        @Size(max = MAX_ITEMS, message = "Mỗi lần bàn giao tối đa 100 phòng ban.")
        List<@NotNull(message = "Vui lòng chọn phòng ban và người nhận bàn giao.") @Valid DepartmentItem> departments) {

    // A copy of RequisitionRequest.NO_NUL_CHARACTER, which is private to the requisition package.
    static final String NO_NUL_CHARACTER = "[^\\x00]*";
    static final int MAX_ITEMS = 100;

    public AccountHandoverRequest {
        note = note == null || note.isBlank() ? null : note;
        requisitions = requisitions == null ? List.of() : requisitions;
        departments = departments == null ? List.of() : departments;
    }

    // One requisition and the person who receives it.
    public record Item(
            @NotNull(message = "Vui lòng chọn yêu cầu tuyển dụng cần bàn giao.") UUID requisitionId,
            @NotNull(message = "Vui lòng chọn người nhận bàn giao.") UUID toUserId) {

        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported account handover field");
        }
    }

    // One department the account manages and its new manager.
    public record DepartmentItem(
            @NotNull(message = "Vui lòng chọn phòng ban cần bàn giao.") UUID departmentId,
            @NotNull(message = "Vui lòng chọn người nhận bàn giao.") UUID toUserId) {

        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported account handover field");
        }
    }

    /**
     * The rules bean validation cannot express, as fieldErrors: at least one item in the two lists together (named
     * "requisitions", the field of the first list), each requisition and each department once (a repeat is named at
     * its own position), and never the account itself as the recipient (the path id is not in the body). Only call it
     * after @Valid has accepted the request. The service checks it before it locks anything.
     */
    Map<String, String> errorsFor(UUID accountId) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (requisitions.isEmpty() && departments.isEmpty()) {
            errors.put("requisitions", "Vui lòng chọn ít nhất một công việc để bàn giao.");
            return errors;
        }
        Set<UUID> seen = new HashSet<>();
        for (int index = 0; index < requisitions.size(); index++) {
            Item item = requisitions.get(index);
            if (!seen.add(item.requisitionId())) {
                errors.put(field(index) + ".requisitionId", "Mỗi yêu cầu tuyển dụng chỉ được bàn giao một lần.");
            }
            if (item.toUserId().equals(accountId)) {
                errors.put(field(index) + ".toUserId", "Người nhận phải khác tài khoản đang bàn giao.");
            }
        }
        Set<UUID> seenDepartments = new HashSet<>();
        for (int index = 0; index < departments.size(); index++) {
            DepartmentItem item = departments.get(index);
            if (!seenDepartments.add(item.departmentId())) {
                errors.put(departmentField(index) + ".departmentId", "Mỗi phòng ban chỉ được bàn giao một lần.");
            }
            if (item.toUserId().equals(accountId)) {
                errors.put(departmentField(index) + ".toUserId", "Người nhận phải khác tài khoản đang bàn giao.");
            }
        }
        return errors;
    }

    // The request field of one item, as bean validation names it: "requisitions[0]".
    static String field(int index) {
        return "requisitions[" + index + "]";
    }

    // The same for a department: "departments[0]".
    static String departmentField(int index) {
        return "departments[" + index + "]";
    }

    // role, from... are not part of the request.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported account handover field");
    }
}
