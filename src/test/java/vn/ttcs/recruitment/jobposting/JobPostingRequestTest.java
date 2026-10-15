package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 304: both request bodies prepare their texts before the validation runs: the title loses the spaces at both
// ends, the texts get \n line breaks and keep their indentation, and a blank text becomes null (inherited on create,
// "không được để trống" on save). Unknown fields are refused while the JSON is read.
class JobPostingRequestTest {
    private static final UUID REQUISITION = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Test
    void theTitleLosesTheSpacesAtBothEndsOnly() {
        var request = create("\u00a0 Lập trình viên  Java\t\r\n", null, null, null);

        assertThat(request.title()).isEqualTo("Lập trình viên  Java");
    }

    @Test
    void textsGetLineFeedsAndKeepTheirIndentationAndEmptyLines() {
        var request = create(null, "  Phát triển API.\r\n\r\n    - Spring Boot\r- PostgreSQL\r\n",
                "Hai năm Java.\rĐọc tiếng Anh.", "\tBảo hiểm.\r\n\tThưởng tháng 13.");

        assertThat(request.jobDescription()).isEqualTo("  Phát triển API.\n\n    - Spring Boot\n- PostgreSQL\n");
        assertThat(request.candidateRequirements()).isEqualTo("Hai năm Java.\nĐọc tiếng Anh.");
        assertThat(request.benefits()).isEqualTo("\tBảo hiểm.\n\tThưởng tháng 13.");
    }

    @Test
    void blankTextsBecomeNull() {
        var request = create(" \u00a0\t\u3000 ", " \r\n\t", "\r", "");

        assertThat(request.title()).isNull();
        assertThat(request.jobDescription()).isNull();
        assertThat(request.candidateRequirements()).isNull();
        assertThat(request.benefits()).isNull();
        // An invisible character is not a space: @PlainText refuses it instead of the text being dropped.
        assertThat(create("\u200b", "\u200b", null, null).title()).isEqualTo("\u200b");
        // String.isBlank() keeps the non-breaking spaces, so a text made only of them is not dropped either: @PlainText
        // refuses it. Only the title strips them first.
        var nonBreaking = create("\u00a0\u202f", "\u00a0\u2007\u202f", "\u00a0", "\u202f");
        assertThat(nonBreaking.title()).isNull();
        assertThat(nonBreaking.jobDescription()).isEqualTo("\u00a0\u2007\u202f");
        assertThat(nonBreaking.candidateRequirements()).isEqualTo("\u00a0");
        assertThat(nonBreaking.benefits()).isEqualTo("\u202f");
    }

    @Test
    void theSaveBodyPreparesItsTextsTheSameWay() {
        var request = new JobPostingRequest(2, "  Kỹ sư phần mềm\r\n", "Dòng một\r\n  Dòng hai\r\n", " \r\n",
                "Quyền lợi\rmới", null, null, LocalDate.of(2026, 11, 30), false);

        assertThat(request.title()).isEqualTo("Kỹ sư phần mềm");
        assertThat(request.jobDescription()).isEqualTo("Dòng một\n  Dòng hai\n");
        assertThat(request.candidateRequirements()).isNull();
        assertThat(request.content()).isEqualTo(new JobPostingContent("Kỹ sư phần mềm", "Dòng một\n  Dòng hai\n",
                null, "Quyền lợi\nmới", null, null, LocalDate.of(2026, 11, 30), false));
    }

    @Test
    void jsonGoesThroughTheSameConstructor() {
        var mapper = JsonMapper.builder().build();

        var request = mapper.readValue("{\"requisitionId\":\"" + REQUISITION + "\",\"title\":\" Java \","
                + "\"jobDescription\":\"a\\r\\nb\",\"candidateRequirements\":\"  \"}", JobPostingCreateRequest.class);

        assertThat(request.title()).isEqualTo("Java");
        assertThat(request.jobDescription()).isEqualTo("a\nb");
        assertThat(request.candidateRequirements()).isNull();
    }

    @Test
    void unknownFieldsAreRefused() {
        var request = create(null, null, null, null);
        var save = new JobPostingRequest(1, "Java", "Mô tả", "Yêu cầu", null, null, null, null, false);
        var mapper = JsonMapper.builder().build();

        assertThatThrownBy(() -> request.rejectUnknownField("status", "PUBLISHED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> save.rejectUnknownField("requisitionId", REQUISITION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"requisitionId\":\"" + REQUISITION + "\",\"salaryMin\":1}",
                JobPostingCreateRequest.class)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private static JobPostingCreateRequest create(String title, String jobDescription, String candidateRequirements,
                                                  String benefits) {
        return new JobPostingCreateRequest(REQUISITION, title, jobDescription, candidateRequirements, benefits, null,
                null, null, null);
    }
}
