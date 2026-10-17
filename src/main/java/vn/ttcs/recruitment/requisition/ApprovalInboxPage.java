package vn.ttcs.recruitment.requisition;

import java.util.List;

// Task 267: one page of the approver inbox (GET /requisitions/approval-inbox), paged like GET /requisitions. Its own
// record so the list page can carry list-only fields (task 298) while the inbox keeps the requisition view.
public record ApprovalInboxPage(List<RequisitionView> items, int page, int size,
                                long totalElements, long totalPages) { }
