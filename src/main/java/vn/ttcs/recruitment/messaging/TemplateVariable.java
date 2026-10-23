package vn.ttcs.recruitment.messaging;

import java.util.Arrays;
import java.util.Optional;

/**
 * Task 531: the values a template may insert, written {{name}} in a subject or a body. The names are fixed English
 * identifiers so a template never depends on the display language; label is what the editor shows next to each one.
 */
public enum TemplateVariable {
    CANDIDATE_NAME("candidateName", "Tên ứng viên"),
    POSITION_TITLE("positionTitle", "Tên vị trí ứng tuyển"),
    INTERVIEW_TIME("interviewTime", "Thời gian phỏng vấn"),
    COMPANY_NAME("companyName", "Tên công ty");

    private final String placeholder;
    private final String label;

    TemplateVariable(String placeholder, String label) {
        this.placeholder = placeholder;
        this.label = label;
    }

    public String placeholder() { return placeholder; }

    public String label() { return label; }

    public static Optional<TemplateVariable> byPlaceholder(String name) {
        return Arrays.stream(values()).filter(variable -> variable.placeholder.equals(name)).findFirst();
    }
}
