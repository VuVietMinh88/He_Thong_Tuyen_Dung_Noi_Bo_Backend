package vn.ttcs.recruitment.messaging;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static vn.ttcs.recruitment.messaging.TemplateVariable.CANDIDATE_NAME;
import static vn.ttcs.recruitment.messaging.TemplateVariable.COMPANY_NAME;
import static vn.ttcs.recruitment.messaging.TemplateVariable.INTERVIEW_TIME;
import static vn.ttcs.recruitment.messaging.TemplateVariable.POSITION_TITLE;

/**
 * Task 531 (story S8-01): the five candidate emails with an editable template. variables is the exact list V66 stores
 * in email_templates.supported_variables for the type (EmailTemplateMigrationIntegrationTest keeps both equal);
 * mandatoryContent says, for the editor, what the system always adds after the template text.
 */
public enum EmailTemplateType {
    APPLICATION_RECEIPT("Xác nhận đã nhận hồ sơ", List.of(CANDIDATE_NAME, POSITION_TITLE, COMPANY_NAME),
            "Vị trí, thời gian tiếp nhận, mã tra cứu, liên kết tra cứu và liên kết rút hồ sơ (riêng cho ứng viên)."),
    INTERVIEW_INVITATION("Mời phỏng vấn", List.of(CANDIDATE_NAME, POSITION_TITLE, INTERVIEW_TIME, COMPANY_NAME),
            "Vị trí, vòng, thời gian, hình thức, địa điểm hoặc liên kết phòng họp, hướng dẫn chuẩn bị và liên kết"
                    + " phản hồi lời mời (riêng cho ứng viên)."),
    INTERVIEW_THANKS("Cảm ơn sau phỏng vấn", List.of(CANDIDATE_NAME, POSITION_TITLE, INTERVIEW_TIME, COMPANY_NAME),
            "Không có nội dung bắt buộc ngoài phần chân email."),
    REJECTION("Thư từ chối", List.of(CANDIDATE_NAME, POSITION_TITLE, COMPANY_NAME),
            "Không có nội dung bắt buộc ngoài phần chân email."),
    OFFER("Thư offer", List.of(CANDIDATE_NAME, POSITION_TITLE, COMPANY_NAME),
            "Điều khoản đã duyệt (lương, phụ cấp, ngày bắt đầu, hạn phản hồi) và liên kết chấp nhận hoặc từ chối"
                    + " (riêng cho ứng viên).");

    private final String displayName;
    private final List<TemplateVariable> variables;
    private final String mandatoryContent;

    EmailTemplateType(String displayName, List<TemplateVariable> variables, String mandatoryContent) {
        this.displayName = displayName;
        this.variables = List.copyOf(variables);
        this.mandatoryContent = mandatoryContent;
    }

    public String displayName() { return displayName; }

    public List<TemplateVariable> variables() { return variables; }

    public String mandatoryContent() { return mandatoryContent; }

    /** Whether rendering this type needs an interview (its time is a supported variable). */
    public boolean needsInterview() { return variables.contains(INTERVIEW_TIME); }

    /** The type named in a path, matched exactly in upper case; empty for anything else. */
    public static Optional<EmailTemplateType> parse(String value) {
        if (value == null || !value.equals(value.toUpperCase(Locale.ROOT))) return Optional.empty();
        for (EmailTemplateType type : values()) {
            if (type.name().equals(value)) return Optional.of(type);
        }
        return Optional.empty();
    }
}
