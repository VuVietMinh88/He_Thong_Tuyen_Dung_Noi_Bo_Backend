package vn.ttcs.recruitment.requisition;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Task 297 (story S3-08): the filters of GET /requisitions after binding. Each one only narrows what the caller may
// already see; the department scope stays in the query. statuses empty = every status.
record RequisitionListFilter(Set<RequisitionStatus> statuses, UUID departmentId, UUID recruiterId,
                             LocalDate neededFrom, LocalDate neededTo) {

    // "status=DRAFT," binds to [DRAFT, null]: the empty element is dropped (EnumSet.copyOf would throw, a 500).
    static RequisitionListFilter of(List<RequisitionStatus> status, UUID departmentId, UUID recruiterId,
                                    LocalDate startDate, LocalDate endDate) {
        Set<RequisitionStatus> statuses = EnumSet.noneOf(RequisitionStatus.class);
        if (status != null) {
            status.stream().filter(Objects::nonNull).forEach(statuses::add);
        }
        return new RequisitionListFilter(Collections.unmodifiableSet(statuses), departmentId, recruiterId,
                startDate, endDate);
    }

    // A start after the end matches nothing and is a mistake. Years outside 1..9999 cannot come from the date input
    // and could reach PostgreSQL as an out-of-range date (a 500).
    boolean neededByRangeValid() {
        return validYear(neededFrom) && validYear(neededTo)
                && (neededFrom == null || neededTo == null || !neededFrom.isAfter(neededTo));
    }

    private static boolean validYear(LocalDate date) {
        return date == null || (date.getYear() >= 1 && date.getYear() <= 9999);
    }
}
