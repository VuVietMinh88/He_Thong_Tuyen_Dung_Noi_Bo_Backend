package vn.ttcs.recruitment.jobposting;

import java.util.List;

public record PublicJobPostingPage(List<PublicJobPostingSummary> items, int page, int size,
                                   long totalElements, long totalPages) { }
