package vn.ttcs.recruitment.candidatesearch;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Task 424 (story S6-01): the filter an export applied, in words, for the workbook's information sheet. Names are
 * resolved by the caller (the position, the source and tag names); no id or internal value is written.
 */
final class CandidateExportDescription {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    private CandidateExportDescription() { }

    record Position(String code, String name) { }

    static List<CandidateExportWorkbook.InfoLine> lines(CandidateSearchCriteria criteria, Position position,
                                                        List<String> sourceNames, List<String> tagNames) {
        List<CandidateExportWorkbook.InfoLine> lines = new ArrayList<>();
        lines.add(line("Vị trí", position == null ? "Tất cả trong phạm vi được xem"
                : position.code() + " - " + position.name()));
        lines.add(line("Giai đoạn", criteria.stages().isEmpty() ? "Tất cả trừ Ứng viên rút"
                : String.join(", ", criteria.effectiveStages().stream()
                        .map(stage -> ApplicationStage.valueOf(stage).displayName()).toList())));
        if (!sourceNames.isEmpty()) lines.add(line("Nguồn", String.join(", ", sourceNames)));
        if (!tagNames.isEmpty()) {
            String match = switch (criteria.tagMatch()) {
                case ANY -> " (có ít nhất một nhãn)";
                case ALL -> " (có đủ các nhãn)";
            };
            lines.add(line("Nhãn", String.join(", ", tagNames) + match));
        }
        if (criteria.submittedFrom() != null || criteria.submittedTo() != null) {
            lines.add(line("Ngày nộp", "từ " + date(criteria.submittedFrom()) + " đến " + date(criteria.submittedTo())));
        }
        if (criteria.minRating() != null || criteria.maxRating() != null) {
            lines.add(line("Đánh giá", "từ " + (criteria.minRating() == null ? 1 : criteria.minRating()) + " đến "
                    + (criteria.maxRating() == null ? 5 : criteria.maxRating())));
        }
        if (criteria.keyword() != null) lines.add(line("Từ khóa (tên, email, điện thoại)", criteria.keyword()));
        if (criteria.coverLetterKeyword() != null) {
            lines.add(line("Từ khóa trong thư giới thiệu", criteria.coverLetterKeyword()));
        }
        return List.copyOf(lines);
    }

    private static String date(LocalDate date) {
        return date == null ? "..." : DATE.format(date);
    }

    private static CandidateExportWorkbook.InfoLine line(String label, String value) {
        return new CandidateExportWorkbook.InfoLine(label, value);
    }
}
