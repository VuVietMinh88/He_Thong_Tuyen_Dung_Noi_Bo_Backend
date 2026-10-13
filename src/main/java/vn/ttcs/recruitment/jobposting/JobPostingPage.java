package vn.ttcs.recruitment.jobposting;

import java.util.List;

public record JobPostingPage(List<JobPostingSummary> items, int page, int size,
                             long totalElements, long totalPages) { }
