package vn.ttcs.recruitment.requisition;

import java.util.List;

public record RequisitionPage(List<RequisitionView> items, int page, int size,
                              long totalElements, long totalPages) { }
