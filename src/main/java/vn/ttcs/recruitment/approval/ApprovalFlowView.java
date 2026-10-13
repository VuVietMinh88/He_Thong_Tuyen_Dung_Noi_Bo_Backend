package vn.ttcs.recruitment.approval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovalFlowView(UUID id, UUID departmentId, int version, String name,
                               UUID createdBy, Instant createdAt, List<ApprovalFlowRequest.Step> steps) {
    public record Summary(UUID id, UUID departmentId, int version, String name, UUID createdBy, Instant createdAt) { }
    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages) { }
}
