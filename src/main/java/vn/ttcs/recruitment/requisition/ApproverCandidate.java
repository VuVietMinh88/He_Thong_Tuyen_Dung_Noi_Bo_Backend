package vn.ttcs.recruitment.requisition;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 256: an account as ApproverRule sees it. accessAllowed: enabled and not locked by an administrator (a temporary
 * login lockout does not count). roles are role codes; permissions are the account's current permission codes.
 * managedDepartmentIds (the departments the account manages, with every department below them) is only read when a
 * department-bound role step needs it.
 */
public record ApproverCandidate(UUID id, String fullName, boolean accessAllowed, Set<String> roles,
                                Set<String> permissions, Supplier<Set<UUID>> managedDepartmentIds) {
    public ApproverCandidate {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
        managedDepartmentIds = memoized(managedDepartmentIds);
    }

    private static Supplier<Set<UUID>> memoized(Supplier<Set<UUID>> source) {
        return new Supplier<>() {
            private Set<UUID> value;

            @Override
            public synchronized Set<UUID> get() {
                if (value == null) {
                    value = Set.copyOf(source.get());
                }
                return value;
            }
        };
    }
}
