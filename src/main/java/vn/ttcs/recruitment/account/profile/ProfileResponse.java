package vn.ttcs.recruitment.account.profile;

import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.Role;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

// hasAvatar and avatarUpdatedAt were added after the first version; older clients simply ignore them. The picture
// itself is read from GET /api/v1/profile/avatar.
public record ProfileResponse(UUID id, String email, String fullName, String phone, String displayTitle,
                              UUID departmentId, String departmentName, Set<Role> roles,
                              boolean hasAvatar, Instant avatarUpdatedAt) {
    /** {@code avatarUpdatedAt} is null when the account has no avatar. */
    public static ProfileResponse from(Account account, String departmentName, Instant avatarUpdatedAt) {
        return new ProfileResponse(account.getId(), account.getEmail(), account.getFullName(),
                account.getPhone(), account.getDisplayTitle(), account.getDepartmentId(), departmentName,
                account.getRoles(), avatarUpdatedAt != null, avatarUpdatedAt);
    }
}
