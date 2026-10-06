package vn.ttcs.recruitment.department;

import java.time.Instant;
import java.util.UUID;

public record DepartmentView(UUID id, String code, String name, UUID parentId, UUID managerUserId,
                             String managerFullName, boolean active, Instant createdAt) { }
