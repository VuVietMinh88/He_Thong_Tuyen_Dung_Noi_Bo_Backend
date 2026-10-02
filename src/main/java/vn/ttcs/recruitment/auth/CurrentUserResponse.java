package vn.ttcs.recruitment.auth;

import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.Role;

import java.util.Set;
import java.util.UUID;

public record CurrentUserResponse(UUID id, String email, String fullName, Set<Role> roles) {

    public static CurrentUserResponse from(Account account) {
        return new CurrentUserResponse(account.getId(), account.getEmail(),
                account.getFullName(), account.getRoles());
    }
}
