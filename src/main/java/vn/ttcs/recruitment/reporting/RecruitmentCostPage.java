package vn.ttcs.recruitment.reporting;

import java.time.LocalDate;
import java.util.List;

/**
 * Task 573: a page of cost entries. activeEntries and activeTotalVnd sum the entries that are not voided among all
 * those matching the filters (not only this page); totalElements counts the listed entries (voided ones included when
 * includeVoided is true).
 */
public record RecruitmentCostPage(String scope, ReportFilters filters, LocalDate from, LocalDate to, String costType,
                                  boolean includeVoided, long activeEntries, long activeTotalVnd,
                                  List<RecruitmentCostView> items, int page, int size, long totalElements,
                                  int totalPages) { }
