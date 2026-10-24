package vn.ttcs.recruitment.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tasks 555-557 (story S8-05): the HR dashboard's metric cards, the overdue requisitions and the charts, all from one
 * snapshot and all inside the viewer's scope and the filters. scope is ALL or SCOPED (ReportViewer.scopeName).
 */
public record DashboardReportView(Instant generatedAt, LocalDate businessDate, String businessZone, String scope,
                                  Filters filters, Cards cards, WeekRange currentWeek, int interviewHorizonDays,
                                  OverduePage overdueRequisitions, DashboardChartsView charts) {

    /** The structural filters applied to every part of the dashboard (null when not set). */
    public record Filters(UUID departmentId, UUID positionId) { }

    /** A card the viewer may see has available=true and a value; otherwise value is null and the reason says why. */
    public record Card(boolean available, Long value, String unavailableReason) {
        static Card of(long value) {
            return new Card(true, value, null);
        }

        static Card unavailable(String reason) {
            return new Card(false, null, reason);
        }
    }

    public record Cards(Card openRequisitions, Card pausedRequisitions, Card newApplicationsThisWeek,
                        Card upcomingInterviews, Card pendingOffers) { }

    /** The business week of businessDate, Monday to Sunday inclusive. */
    public record WeekRange(LocalDate from, LocalDate to) { }

    /** fullyStaffed: the whole headcount is hired but the requisition is still open; such rows come last. */
    public record OverdueRequisition(UUID requisitionId, UUID positionId, String positionName, UUID departmentId,
                                     String departmentName, String status, LocalDate neededBy, long daysOverdue,
                                     int headcount, long hiredCount, long remainingHeadcount,
                                     boolean fullyStaffed) { }

    public record OverduePage(List<OverdueRequisition> items, int page, int size, long totalElements,
                              int totalPages) { }
}
