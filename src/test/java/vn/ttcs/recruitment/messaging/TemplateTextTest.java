package vn.ttcs.recruitment.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateTextTest {
    private static final Map<TemplateVariable, String> VALUES = Map.of(
            TemplateVariable.CANDIDATE_NAME, "Nguyễn Văn A",
            TemplateVariable.POSITION_TITLE, "Lập trình viên Java",
            TemplateVariable.INTERVIEW_TIME, "09:00 Thứ Ba, ngày 20/10/2026",
            TemplateVariable.COMPANY_NAME, "TTCS");

    @ParameterizedTest
    @EnumSource(EmailTemplateType.class)
    void everySupportedVariableIsAcceptedInSubjectAndBody(EmailTemplateType type) {
        String all = String.join(" ", type.variables().stream().map(v -> "{{" + v.placeholder() + "}}").toList());
        assertThat(TemplateText.problems(type, "Thư " + all, "Chào " + all + "\n\nNội dung.")).isEmpty();
    }

    @Test
    void variableOfAnotherTypeIsRefusedAndNamed() {
        var problems = TemplateText.problems(EmailTemplateType.REJECTION, "Kết quả", "Lịch {{interviewTime}} và {{salary}}");
        assertThat(problems).containsOnlyKeys("body");
        assertThat(problems.get("body")).contains("interviewTime", "salary", "candidateName");
        assertThat(TemplateText.problems(EmailTemplateType.INTERVIEW_THANKS, "Lúc {{interviewTime}}", "Cảm ơn")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Chào {{ candidateName }}", "Chào {{candidateName}", "Chào {{candidate_name}}",
            "Chào {{}}", "Chào }} bạn", "Chào {{{candidateName}}}", "{{candidateName}} {{"})
    void malformedPlaceholdersAreRefused(String body) {
        assertThat(TemplateText.problems(EmailTemplateType.REJECTION, "Kết quả", body))
                .containsOnlyKeys("body").extractingByKey("body").asString().contains("{{tenBien}}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Thư\r\nBcc: x@evil.test", "Thư\nmời", "Thư\rmời", "Thư\tmời", "Thư\u0000mời"})
    void subjectMustStayOnOneHeaderLine(String subject) {
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, subject, "Nội dung")).containsOnlyKeys("subject");
    }

    @Test
    void bodyKeepsLineBreaksButRefusesOtherControlsHtmlAndEmptyText() {
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, "Thư", "Dòng 1\nDòng 2\n\n\tĐoạn 2")).isEmpty();
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, "Thư", "Dòng\rhai")).containsOnlyKeys("body");
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, "Thư", "<b>Đậm</b>")).containsOnlyKeys("body");
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, "Thư", "Lương < 20 triệu")).isEmpty();
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, " ", "​​")).containsOnlyKeys("subject", "body");
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, "Thư", "Đổi chiều ‮ văn bản"))
                .containsOnlyKeys("body");
    }

    @Test
    void lengthsAreBounded() {
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, "a".repeat(200), "b".repeat(5000))).isEmpty();
        assertThat(TemplateText.problems(EmailTemplateType.OFFER, "a".repeat(201), "b".repeat(5001)))
                .containsOnlyKeys("subject", "body");
    }

    @Test
    void renderingFillsValuesOnOneLineAndSplitsParagraphs() {
        var values = new java.util.HashMap<>(VALUES);
        values.put(TemplateVariable.CANDIDATE_NAME, "An\r\nBcc: evil@example.test $1 \\");
        String subject = TemplateText.renderSubject("Chào {{candidateName}} - {{positionTitle}}", values);
        assertThat(subject).isEqualTo("Chào An Bcc: evil@example.test $1 \\ - Lập trình viên Java").doesNotContain("\n", "\r");
        var paragraphs = TemplateText.renderBody("Chào {{candidateName}},\n\n  Lịch: {{interviewTime}}\ndòng sau \n \n\nHết", values);
        assertThat(paragraphs).containsExactly("Chào An Bcc: evil@example.test $1 \\,",
                "Lịch: 09:00 Thứ Ba, ngày 20/10/2026\ndòng sau", "Hết");
    }

    @Test
    void missingValueRendersEmptyButAnUnknownVariableCannotBeRendered() {
        assertThat(TemplateText.renderSubject("Chào {{candidateName}}!", Map.of())).isEqualTo("Chào !");
        assertThatThrownBy(() -> TemplateText.renderBody("Chào {{salary}}", VALUES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultTemplatesOfTheMigrationAreValid() {
        // The same texts as V66, so the seeded rows always pass the API's rules.
        assertThat(TemplateText.problems(EmailTemplateType.APPLICATION_RECEIPT,
                "Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ",
                "Chào {{candidateName}},\n\nHệ thống đã nhận hồ sơ ứng tuyển của bạn.")).isEmpty();
        assertThat(TemplateText.problems(EmailTemplateType.INTERVIEW_THANKS, "Cảm ơn bạn đã tham gia phỏng vấn",
                "Xin chào {{candidateName}},\n\nCảm ơn bạn đã dành thời gian tham gia buổi phỏng vấn vị trí"
                        + " {{positionTitle}} lúc {{interviewTime}}.")).isEmpty();
    }
}
