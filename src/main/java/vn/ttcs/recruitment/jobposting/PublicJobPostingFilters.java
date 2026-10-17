package vn.ttcs.recruitment.jobposting;

import java.util.List;

// Task 315 (story S4-01): the values the public job list can be filtered by, taken only from the postings it lists
// today. Departments are ordered by name; work locations and employment types like their catalog (sort order, then
// name). The ids are the values of the departmentId, workLocationId and employmentTypeId filters.
public record PublicJobPostingFilters(List<PublicJobPostingFilterOption> departments,
                                      List<PublicJobPostingFilterOption> workLocations,
                                      List<PublicJobPostingFilterOption> employmentTypes) { }
