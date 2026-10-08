package vn.ttcs.recruitment.department;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record DepartmentTreeNode(UUID id, String code, String name, UUID parentId, UUID managerUserId,
                                 String managerFullName, boolean active, Instant createdAt,
                                 List<DepartmentTreeNode> children) {

    static DepartmentTreeNode from(DepartmentView department) {
        return new DepartmentTreeNode(department.id(), department.code(), department.name(), department.parentId(),
                department.managerUserId(), department.managerFullName(), department.active(), department.createdAt(),
                new ArrayList<>());
    }
}
