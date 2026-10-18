package vn.ttcs.recruitment.candidate;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 350 (story S4-07, "Hồ sơ được gắn nhãn nguồn Giới thiệu nội bộ và tên người giới thiệu"): the source block of
 * one application in the unified profile. A referral row makes it INTERNAL_REFERRAL with the referrer and the note;
 * otherwise it stays task 361's CAREER_PAGE with the catalog name. Absent values are left out of the JSON.
 */
class ApplicationSourceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID REFERRER = UUID.randomUUID();
    private static final String REFERRER_NAME = "Đinh Văn Khoa";
    private static final String NOTE = "Bạn học cũ,\nmạnh Java";

    @Test
    void aReferralCarriesTheReferrerAndTheNoteButNoCatalogName() {
        JsonNode source = JSON.valueToTree(ApplicationSource.referral(REFERRER, REFERRER_NAME, NOTE));

        assertThat(source.propertyNames()).containsExactly("type", "label", "referrerId", "referrerName", "note");
        assertThat(source.path("type").asText()).isEqualTo("INTERNAL_REFERRAL");
        assertThat(source.path("label").asText()).isEqualTo("Giới thiệu nội bộ");
        assertThat(source.path("referrerId").asText()).isEqualTo(REFERRER.toString());
        assertThat(source.path("referrerName").asText()).isEqualTo(REFERRER_NAME);
        assertThat(source.path("note").asText()).isEqualTo(NOTE);
    }

    @Test
    void aCareerPageApplicationKeepsTheKeysOfTask361() {
        JsonNode chosen = JSON.valueToTree(ApplicationSource.careerPage("LinkedIn"));
        assertThat(chosen.propertyNames()).containsExactly("type", "label", "details");
        assertThat(chosen.path("type").asText()).isEqualTo("CAREER_PAGE");
        assertThat(chosen.path("label").asText()).isEqualTo("Trang tuyển dụng");
        assertThat(chosen.path("details").asText()).isEqualTo("LinkedIn");

        assertThat(JSON.valueToTree(ApplicationSource.careerPage(null)).propertyNames())
                .containsExactly("type", "label");
    }

    // The referral row decides, whatever the catalog column holds (a referred application has none, task 350).
    @Test
    void theReferralRowDecidesTheSource() {
        assertThat(row(REFERRER, "LinkedIn").detail(true).source())
                .isEqualTo(ApplicationSource.referral(REFERRER, REFERRER_NAME, NOTE));
        assertThat(row(null, "LinkedIn").detail(true).source()).isEqualTo(ApplicationSource.careerPage("LinkedIn"));
    }

    @Test
    void toStringHidesTheNamesAndTheNote() {
        String text = ApplicationSource.referral(REFERRER, REFERRER_NAME, NOTE).toString();
        assertThat(text).contains("INTERNAL_REFERRAL").doesNotContain(REFERRER_NAME).doesNotContain("mạnh Java");
        assertThat(ApplicationSource.careerPage("LinkedIn").toString()).doesNotContain("LinkedIn");
    }

    private static ReadableApplication row(UUID referrer, String sourceName) {
        return new ReadableApplication(UUID.randomUUID(), UUID.randomUUID(), "Lập trình viên Java",
                Instant.parse("2026-10-15T03:00:00Z"), ApplicationStage.NEW, "Nguyễn Thị Lan", "lan@example.test",
                "0912345678", null, "Phòng CNTT", UUID.randomUUID(), "Phạm Văn Cường", null, null, sourceName,
                "cv.pdf", "application/pdf", 1234, null, null, referrer, referrer == null ? null : REFERRER_NAME,
                referrer == null ? null : NOTE);
    }
}
