package vn.ttcs.recruitment.screening;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.companyprofile.PlainText;

public record ScreeningNoteRequest(@NotBlank @Size(max = 2000) @PlainText(multiline = true) String content) {
    public ScreeningNoteRequest {
        content = content == null ? null : content.replace("\r\n", "\n").replace('\r', '\n');
        if (content != null && content.codePoints().noneMatch(c -> !Character.isWhitespace(c)
                && !Character.isSpaceChar(c) && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                && c != 0x85 && c != 0x180e)) {
            content = null;
        }
    }
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported screening note field");
    }
    @Override
    public String toString() {
        return "ScreeningNoteRequest[content redacted]";
    }
}
