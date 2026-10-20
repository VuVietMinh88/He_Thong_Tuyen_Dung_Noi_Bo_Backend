package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 393 (story S5-05): the keyword inside the application's cover letter, combined with every other condition. */
class CandidateCoverLetterSearchIntegrationTest extends CandidateSearchTestSupport {
    @Test
    void coverLetterKeywordIgnoresAccentsAndCaseAndNeverReturnsTheLetter() throws Exception {
        JsonNode page = search(recruiterToken, "coverLetterKeyword", "SPRING");
        assertThat(ids(page)).containsExactly(binh.applicationId());
        assertThat(item(page, binh.applicationId()).path("matchedIn").toString()).isEqualTo("[\"COVER_LETTER\"]");
        assertThat(page.toString()).doesNotContain("kinh nghiệm", "Tôi có");
        assertThat(ids(search(recruiterToken, "coverLetterKeyword", "kinh nghiem java"))).containsExactly(binh.applicationId());
        assertThat(ids(search(recruiterToken, "coverLetterKeyword", "5 năm"))).containsExactly(binh.applicationId());
        // Applications without a cover letter never match, and wildcards are literal characters.
        assertThat(search(hrToken, "coverLetterKeyword", "%%").path("total").asLong()).isZero();
        assertThat(search(hrToken, "coverLetterKeyword", "J_va").path("total").asLong()).isZero();
    }

    @Test
    void coverLetterKeywordIsCombinedWithTheKeywordAndTheFilters() throws Exception {
        JsonNode both = search(recruiterToken, "keyword", "binh", "coverLetterKeyword", "java");
        assertThat(ids(both)).containsExactly(binh.applicationId());
        assertThat(item(both, binh.applicationId()).path("matchedIn").toString())
                .isEqualTo("[\"NAME\",\"EMAIL\",\"COVER_LETTER\"]");
        assertThat(ids(search(recruiterToken, "keyword", "nguyen", "coverLetterKeyword", "java"))).isEmpty();
        assertThat(ids(search(recruiterToken, "coverLetterKeyword", "java", "stage", "SCREENING"))).isEmpty();
        assertThat(ids(search(recruiterToken, "coverLetterKeyword", "java", "sourceId", linkedIn.toString())))
                .containsExactly(binh.applicationId());
        applications.stage(binh.applicationId(), ApplicationStage.SCREENING);
        assertThat(ids(search(recruiterToken, "coverLetterKeyword", "java", "stage", "SCREENING")))
                .containsExactly(binh.applicationId());
    }

    @Test
    void coverLetterKeywordNeverReachesOutsideTheScope() throws Exception {
        var outside = applications.stored(postingOf(otherRequisition), new ApplicationSubmission("Phạm Minh Châu",
                "chau@example.test", "0933444555", null, "Thành thạo Java, Spring và Kafka."));
        assertThat(ids(search(recruiterToken, "coverLetterKeyword", "spring"))).containsExactly(binh.applicationId());
        assertThat(ids(search(token(outsider), "coverLetterKeyword", "spring"))).containsExactly(outside.applicationId());
        assertThat(ids(search(hrToken, "coverLetterKeyword", "spring")))
                .containsExactlyInAnyOrder(binh.applicationId(), outside.applicationId());
        assertThat(granted(recruiter)).isEqualTo(1);
    }

    @Test
    void theTotalComesWithThePageAndStaysRightPastTheLastPage() throws Exception {
        JsonNode firstPage = search(hrToken, "keyword", "example.test", "size", "2");
        assertThat(firstPage.path("total").asLong()).isEqualTo(3);
        assertThat(firstPage.path("items").size()).isEqualTo(2);
        assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
        JsonNode beyond = search(hrToken, "keyword", "example.test", "size", "2", "page", "5");
        assertThat(beyond.path("items").size()).isZero();
        assertThat(beyond.path("total").asLong()).isEqualTo(3);
        assertThat(search(hrToken, "keyword", "khong ai ten nay").path("total").asLong()).isZero();
    }

    @Test
    void invalidCoverLetterKeywordIsRejected() throws Exception {
        var response = call("GET", SEARCH + query("coverLetterKeyword", "j"), null, hrToken);
        assertError(response, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(response.body()).path("fieldErrors").has("coverLetterKeyword")).isTrue();
        assertError(call("GET", SEARCH + query("coverLetterKeyword", "x".repeat(101)), null, hrToken), 400,
                "VALIDATION_ERROR");
    }
}
