package vn.ttcs.recruitment.account.role;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.account.Role;

import java.util.Objects;
import java.util.Set;

// The complete set of roles the account holds after PUT /accounts/{id}/roles. Duplicates collapse. expectedRoles is
// the set the edit page loaded, so a save made on an outdated view cannot undo a grant or revocation that another
// administrator committed meanwhile (409 ACCOUNT_ROLES_CHANGED). It may be empty: an account can lose its last role
// through DELETE /accounts/{id}/roles/{role}.
public record AccountRolesRequest(
        @NotEmpty(message = "Tài khoản phải có ít nhất một vai trò.")
        @Size(max = 6, message = "Tài khoản có tối đa 6 vai trò.")
        @JsonDeserialize(contentUsing = RoleCodeDeserializer.class) Set<Role> roles,
        @NotNull(message = "Thiếu danh sách vai trò hiện tại của tài khoản đang xem.")
        @Size(max = 6, message = "Tài khoản có tối đa 6 vai trò.")
        @JsonDeserialize(contentUsing = RoleCodeDeserializer.class) Set<Role> expectedRoles) {
    public AccountRolesRequest {
        // A null code names no role. [null] or ["ADMIN", null] fails with the same error as a missing set, instead of
        // dropping the null and using the rest.
        roles = copyOrNullWhenACodeIsNull(roles);
        expectedRoles = copyOrNullWhenACodeIsNull(expectedRoles);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported account roles field");
    }

    private static Set<Role> copyOrNullWhenACodeIsNull(Set<Role> roles) {
        return roles == null || roles.stream().anyMatch(Objects::isNull) ? null : Set.copyOf(roles);
    }
}
