package vn.ttcs.recruitment.requisition;

import java.util.List;

public record RequisitionPage(List<RequisitionListItemView> items, int page, int size,
                              long totalElements, long totalPages) { }
