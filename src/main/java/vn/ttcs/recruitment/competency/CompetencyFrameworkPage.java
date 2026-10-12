package vn.ttcs.recruitment.competency;

import java.util.List;

public record CompetencyFrameworkPage(List<CompetencyFrameworkSummary> items, int page, int size,
                                      long totalElements, long totalPages) { }
