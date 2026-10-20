package vn.ttcs.recruitment.reporting;

import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.reporting.TimeCostReportView.Metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static vn.ttcs.recruitment.reporting.ReportSpreadsheet.days;
import static vn.ttcs.recruitment.reporting.ReportSpreadsheet.money;

/**
 * Task 576 (story S8-08): the time and cost report as an Excel workbook, built from the very TimeCostReportView the
 * JSON endpoint returns (same filters, same scope, same formulas), so the file and the screen can never disagree.
 * Sheets: the filters and definitions, the overall total, the comparison by department and by position, and the
 * requisitions. Money is a numeric cell with thousands separators, days a numeric cell with one decimal, a missing value
 * an empty cell (never a misleading zero).
 */
@Component
public class TimeCostWorkbook {
    static final List<String> METRIC_HEADERS = List.of("Số người nhận việc", "Có offer được chấp nhận",
            "Đã hoàn tất hội nhập", "Số mẫu thời gian tuyển", "Thời gian tuyển TB (ngày)", "Nhanh nhất (ngày)",
            "Chậm nhất (ngày)", "Thiếu mốc duyệt", "Ngày không hợp lệ", "Chi phí đăng tin (VND)",
            "Thưởng giới thiệu (VND)", "Tổng chi phí (VND)", "Số khoản chi phí", "Chi phí mỗi người (VND)");

    byte[] workbook(TimeCostReportView report) {
        try (var spreadsheet = new ReportSpreadsheet()) {
            Sheet filters = spreadsheet.sheet("Bộ lọc", List.of("Mục", "Giá trị"), 12);
            spreadsheet.row(filters, "Từ ngày", report.from());
            spreadsheet.row(filters, "Đến ngày", report.to());
            spreadsheet.row(filters, "Phạm vi dữ liệu", "ALL".equals(report.scope()) ? "Toàn công ty"
                    : "Các vị trí người xuất được phụ trách");
            spreadsheet.row(filters, "Yêu cầu tuyển dụng", text(report.filters().requisitionId()));
            spreadsheet.row(filters, "Phòng ban", text(report.filters().departmentId()));
            spreadsheet.row(filters, "Chức danh", text(report.filters().positionId()));
            spreadsheet.row(filters, "Thời điểm lập (UTC)", report.generatedAt().toString());
            spreadsheet.row(filters, "Người nhận việc", "Lần đầu hồ sơ chuyển sang Nhận việc (HIRED) trong kỳ");
            spreadsheet.row(filters, "Thời gian tuyển", "Ngày nhận việc trừ ngày duyệt cuối của yêu cầu; trung bình theo từng người");
            spreadsheet.row(filters, "Chi phí mỗi người", "Tổng chi phí chưa hủy phát sinh trong kỳ chia số người nhận việc");

            Sheet overall = spreadsheet.sheet("Tổng hợp", METRIC_HEADERS, 1);
            spreadsheet.row(overall, metrics(report.overall()).toArray());

            writeGroups(spreadsheet, "Theo phòng ban", "Phòng ban", report.byDepartment());
            writeGroups(spreadsheet, "Theo chức danh", "Chức danh", report.byPosition());

            List<String> requisitionHeaders = new ArrayList<>(List.of("Mã yêu cầu", "Phòng ban", "Chức danh",
                    "Trạng thái", "Ngày duyệt"));
            requisitionHeaders.addAll(METRIC_HEADERS);
            Sheet requisitions = spreadsheet.sheet("Theo yêu cầu", requisitionHeaders, report.requisitions().size());
            for (var row : report.requisitions()) {
                List<Object> values = new ArrayList<>(List.of(row.requisitionId().toString(), row.departmentName(),
                        row.positionName(), row.status()));
                values.add(row.approvedOn());
                values.addAll(metrics(row.metrics()));
                spreadsheet.row(requisitions, values.toArray());
            }
            return spreadsheet.bytes();
        }
    }

    static String filename(TimeCostReportView report) {
        return "bao-cao-thoi-gian-chi-phi_" + report.from() + "_" + report.to() + ".xlsx";
    }

    private static void writeGroups(ReportSpreadsheet spreadsheet, String sheetName, String nameHeader,
                                    List<TimeCostReportView.Group> groups) {
        List<String> headers = new ArrayList<>(List.of("Mã", nameHeader, "Số yêu cầu"));
        headers.addAll(METRIC_HEADERS);
        Sheet sheet = spreadsheet.sheet(sheetName, headers, groups.size());
        for (var group : groups) {
            List<Object> values = new ArrayList<>(List.of(group.id().toString(), group.name(), group.requisitions()));
            values.addAll(metrics(group.metrics()));
            spreadsheet.row(sheet, values.toArray());
        }
    }

    // ArrayList, not List.of: missing values are null and leave the cell empty.
    private static List<Object> metrics(Metrics metrics) {
        List<Object> values = new ArrayList<>();
        values.add(metrics.hires());
        values.add(metrics.hiresWithAcceptedOffer());
        values.add(metrics.hiresOnboarded());
        values.add(metrics.timeToHire().sampleSize());
        values.add(days(metrics.timeToHire().averageDays()));
        values.add(metrics.timeToHire().minDays());
        values.add(metrics.timeToHire().maxDays());
        values.add(metrics.timeToHire().missingApproval());
        values.add(metrics.timeToHire().invalid());
        values.add(money(metrics.costs().advertisingVnd()));
        values.add(money(metrics.costs().referralBonusVnd()));
        values.add(money(metrics.costs().totalVnd()));
        values.add(metrics.costs().entries());
        values.add(money(metrics.costPerHireVnd()));
        return values;
    }

    private static String text(UUID id) {
        return id == null ? "Tất cả" : id.toString();
    }
}
