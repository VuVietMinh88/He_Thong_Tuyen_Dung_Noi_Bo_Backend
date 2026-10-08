package vn.ttcs.recruitment.account.role;

import vn.ttcs.recruitment.account.Account;

import java.util.List;
import java.util.UUID;

public record AccountRolesResponse(UUID userId, List<String> roles) {
    public static AccountRolesResponse from(Account account) {
        return new AccountRolesResponse(account.getId(),
                account.getRoles().stream().map(Enum::name).sorted().toList());
    }
}
