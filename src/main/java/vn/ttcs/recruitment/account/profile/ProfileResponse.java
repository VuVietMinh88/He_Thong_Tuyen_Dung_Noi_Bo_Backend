package vn.ttcs.recruitment.account.profile;

import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.Role;

import java.util.Set;
import java.util.UUID;

public record ProfileResponse(UUID id, String email, String fullName, String phone, String displayTitle,
                              UUID departmentId, String departmentName, Set<Role> roles) {
    public static ProfileResponse from(Account account, String departmentName) {
        return new ProfileResponse(account.getId(), account.getEmail(), account.getFullName(),
                account.getPhone(), account.getDisplayTitle(), account.getDepartmentId(), departmentName,
                account.getRoles());
    }
}
