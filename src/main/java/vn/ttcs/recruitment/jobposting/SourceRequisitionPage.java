package vn.ttcs.recruitment.jobposting;

import java.util.List;

public record SourceRequisitionPage(List<SourceRequisitionView> items, int page, int size,
                                    long totalElements, long totalPages) { }
