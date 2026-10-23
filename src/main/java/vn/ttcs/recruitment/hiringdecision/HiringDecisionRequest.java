package vn.ttcs.recruitment.hiringdecision;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.companyprofile.PlainText;

import java.util.UUID;

/** The summary hash is an optimistic version of all interview evidence, including reopened drafts. */
public record HiringDecisionRequest(
        @NotNull @Pattern(regexp = "INTERVIEW", message = "Chỉ quyết định hồ sơ đang ở giai đoạn Phỏng vấn.")
        String expectedCurrentStage,
        @NotNull @Pattern(regexp = "OFFER|REJECT", message = "Vui lòng chọn OFFER hoặc REJECT.")
        String decision,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}", message = "Thiếu phiên bản tổng hợp đánh giá đang xem.")
        String expectedEvidenceHash,
        UUID rejectionReasonId,
        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự.")
        @PlainText(multiline = true, message = "Ghi chú chỉ được là văn bản thuần.")
        String note) {
    public HiringDecisionRequest {
        note = normalize(note);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String result = value.replace("\r\n", "\n").replace('\r', '\n');
        boolean visible = result.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)
                && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                && c != 0x0085 && c != 0x180E);
        return visible ? result : null;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported hiring decision field");
    }

    @Override public String toString() { return "HiringDecisionRequest[" + decision + ", note redacted]"; }
}
