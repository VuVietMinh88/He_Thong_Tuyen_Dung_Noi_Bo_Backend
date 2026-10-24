package vn.ttcs.recruitment.reporting;

import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static vn.ttcs.recruitment.reporting.ReportSpreadsheet.rate;

/**
 * Task 582 (story S8-09): the source quality report as an Excel workbook, built from the very SourceReportView the
 * JSON endpoint returns (same cohort, filters, scope, rates and ranking). Counts are numeric cells, rates percentage
 * cells, a rate without a denominator or a missing rank an empty cell; the unknown source keeps its explicit name.
 */
@Component
public class SourceWorkbook {
    static final List<String> HEADERS = List.of("Hạng chất lượng", "Nguồn", "Mã nguồn", "Đang dùng", "Số hồ sơ",
            "Qua sàng lọc", "Tỷ lệ qua sàng lọc", "Nhận việc", "Tỷ lệ nhận việc", "Bị loại", "Rút hồ sơ",
            "Đang xử lý", "Đủ cỡ mẫu");

    byte[] workbook(SourceReportView report) {
        try (var spreadsheet = new ReportSpreadsheet()) {
            Sheet filters = spreadsheet.sheet("Bộ lọc", List.of("Mục", "Giá trị"), 12);
            spreadsheet.row(filters, "Từ ngày", report.from());
            spreadsheet.row(filters, "Đến ngày", report.to());
            spreadsheet.row(filters, "Phạm vi dữ liệu", "ALL".equals(report.scope()) ? "Toàn công ty"
                    : "Các vị trí người xuất được phụ trách");
            spreadsheet.row(filters, "Yêu cầu tuyển dụng", text(report.filters().requisitionId()));
            spreadsheet.row(filters, "Phòng ban", text(report.filters().departmentId()));
            spreadsheet.row(filters, "Chức danh", text(report.filters().positionId()));
            spreadsheet.row(filters, "Cỡ mẫu tối thiểu để xếp hạng", report.minApplications());
            spreadsheet.row(filters, "Tổng số hồ sơ", report.cohortSize());
            spreadsheet.row(filters, "Nguồn chất lượng nhất", report.bestSource() == null ? "Chưa có nguồn đủ mẫu"
                    : report.bestSource().name());
            spreadsheet.row(filters, "Nguồn nhiều hồ sơ nhất", report.mostApplications() == null ? "Chưa có"
                    : report.mostApplications().name());
            spreadsheet.row(filters, "Cách xếp hạng", "Tỷ lệ nhận việc, rồi tỷ lệ qua sàng lọc, rồi số hồ sơ;"
                    + " chỉ nguồn có đủ cỡ mẫu tối thiểu");
            spreadsheet.row(filters, "Thời điểm lập (UTC)", report.generatedAt().toString());

            Sheet sources = spreadsheet.sheet("Theo nguồn", HEADERS, report.sources().size());
            for (var source : report.sources()) {
                List<Object> values = new ArrayList<>();
                values.add(source.qualityRank());
                values.add(source.name());
                values.add(source.code());
                values.add(source.active() == null ? null : source.active() ? "Có" : "Không");
                values.add(source.applications());
                values.add(source.passedScreening());
                values.add(rate(source.screeningPassRate()));
                values.add(source.hired());
                values.add(rate(source.hireRate()));
                values.add(source.rejected());
                values.add(source.withdrawn());
                values.add(source.inProgress());
                values.add(source.meetsMinimumSample() ? "Có" : "Không");
                spreadsheet.row(sources, values.toArray());
            }
            return spreadsheet.bytes();
        }
    }

    static String filename(SourceReportView report) {
        return "bao-cao-chat-luong-nguon_" + report.from() + "_" + report.to() + ".xlsx";
    }

    private static String text(UUID id) {
        return id == null ? "Tất cả" : id.toString();
    }
}
