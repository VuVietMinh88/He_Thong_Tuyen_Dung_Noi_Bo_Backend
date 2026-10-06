package vn.ttcs.recruitment.department;

import java.util.List;

public record DepartmentPage(List<DepartmentView> items, int page, int size,
                             long totalElements, long totalPages) { }
