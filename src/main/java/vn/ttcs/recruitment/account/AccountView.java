package vn.ttcs.recruitment.account;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AccountView(UUID id, String email, String fullName, String phone, String displayTitle,
                          UUID departmentId, String departmentName, Set<Role> roles,
                          AccountStatus status, Instant createdAt) { }
