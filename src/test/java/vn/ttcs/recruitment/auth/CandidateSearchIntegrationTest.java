package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 392 (story S5-05): the multi-criteria candidate search, its scope, its filters and its validation. */
class CandidateSearchIntegrationTest extends CandidateSearchTestSupport {
    @Test
    void everyReaderSeesOnlyApplicationsInItsScopeAndEveryShownProfileIsLogged() throws Exception {
        JsonNode all = search(hrToken);
        assertThat(ids(all)).containsExactlyInAnyOrder(first.applicationId(), other.applicationId(), binh.applicationId());
        assertThat(all.path("total").asLong()).isEqualTo(3);
        assertThat(all.toString()).doesNotContain("trackingCode", "coverLetter", "Tôi có 5 năm", "notes");

        JsonNode assigned = search(recruiterToken);
        assertThat(ids(assigned)).containsExactlyInAnyOrder(first.applicationId(), binh.applicationId());
        assertThat(granted(recruiter)).isEqualTo(2);
        assertThat(ids(search(token(manager)))).containsExactlyInAnyOrder(first.applicationId(), binh.applicationId());
        assertThat(ids(search(token(outsider)))).containsExactly(other.applicationId());

        JsonNode card = item(assigned, binh.applicationId());
        assertThat(card.path("fullName").asText()).isEqualTo("Trần Thị Bình");
        assertThat(card.path("candidateId").asText()).isEqualTo(binh.candidateId().toString());
        assertThat(card.path("requisitionId").asText()).isEqualTo(requisition.toString());
        assertThat(card.path("sourceName").asText()).isEqualTo("LinkedIn");
        assertThat(card.path("stage").asText()).isEqualTo("NEW");
        assertThat(card.path("stageName").asText()).isEqualTo("Hồ sơ mới");
        assertThat(card.path("matchedIn").size()).isZero();

        UUID noRole = applications.account("No role", "none@example.test");
        assertError(call("GET", SEARCH, null, token(noRole)), 403, "FORBIDDEN");
        assertThat(call("GET", SEARCH, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void requisitionAndPositionFiltersAreCheckedBeforeAnyApplicationIsRead() throws Exception {
        assertThat(ids(search(recruiterToken, "requisitionId", requisition.toString())))
                .containsExactlyInAnyOrder(first.applicationId(), binh.applicationId());
        assertError(call("GET", SEARCH + query("requisitionId", otherRequisition.toString()), null, recruiterToken),
                403, "FORBIDDEN");
        assertError(call("GET", SEARCH + query("requisitionId", UUID.randomUUID().toString()), null, recruiterToken),
                404, "REQUISITION_NOT_FOUND");
        assertError(call("GET", SEARCH + query("positionId", positionOf(otherRequisition).toString()), null,
                recruiterToken), 403, "FORBIDDEN");
        assertError(call("GET", SEARCH + query("positionId", UUID.randomUUID().toString()), null, hrToken),
                404, "POSITION_NOT_FOUND");
        assertThat(ids(search(hrToken, "positionId", positionOf(otherRequisition).toString())))
                .containsExactly(other.applicationId());
        assertThat(granted(recruiter)).isEqualTo(2);
    }

    @Test
    void keywordFindsNamesWithoutAccentsEmailsAndPhoneDigitsButNeverWildcards() throws Exception {
        JsonNode name = search(hrToken, "keyword", "nguyen  VAN");
        assertThat(ids(name)).containsExactlyInAnyOrder(first.applicationId(), other.applicationId());
        assertThat(item(name, first.applicationId()).path("matchedIn").toString()).isEqualTo("[\"NAME\"]");

        JsonNode email = search(recruiterToken, "keyword", "BINH@example");
        assertThat(ids(email)).containsExactly(binh.applicationId());
        assertThat(item(email, binh.applicationId()).path("matchedIn").toString()).isEqualTo("[\"EMAIL\"]");

        JsonNode phone = search(recruiterToken, "keyword", "098 765");
        assertThat(ids(phone)).containsExactly(binh.applicationId());
        assertThat(item(phone, binh.applicationId()).path("matchedIn").toString()).isEqualTo("[\"PHONE\"]");

        assertThat(ids(search(hrToken, "keyword", "Trần thị"))).containsExactly(binh.applicationId());
        assertThat(search(hrToken, "keyword", "%%").path("total").asLong()).isZero();
        assertThat(search(hrToken, "keyword", "_a").path("total").asLong()).isZero();
        assertThat(search(hrToken, "keyword", "!%").path("total").asLong()).isZero();
        // The keyword never reaches outside the scope, even when it names the person of an unreadable application.
        assertThat(ids(search(recruiterToken, "keyword", "lan@example"))).containsExactly(first.applicationId());
    }

    @Test
    void phonesMatchInNationalAndInternationalFormAndInvisibleKeywordsAreIgnored() throws Exception {
        // binh was stored with 0987654321; the international form finds it, and so does a stored +84 number.
        assertThat(ids(search(recruiterToken, "keyword", "+84 987 654"))).containsExactly(binh.applicationId());
        jdbc.update("UPDATE job_applications SET phone = '+84912345678' WHERE id = ?", first.applicationId());
        JsonNode national = search(recruiterToken, "keyword", "0912 345");
        assertThat(ids(national)).containsExactly(first.applicationId());
        assertThat(item(national, first.applicationId()).path("matchedIn").toString()).isEqualTo("[\"PHONE\"]");
        assertThat(ids(search(recruiterToken, "keyword", "84912"))).containsExactly(first.applicationId());

        String nbsp = String.valueOf((char) 0x00A0);
        String zeroWidth = String.valueOf((char) 0x200B);
        // Only no-break or zero-width spaces: no keyword at all, like an empty field.
        assertThat(search(recruiterToken, "keyword", nbsp + nbsp + zeroWidth + nbsp).path("total").asLong()).isEqualTo(2);
        assertThat(ids(search(recruiterToken, "keyword", nbsp + "tran" + nbsp + "thi" + nbsp)))
                .containsExactly(binh.applicationId());
    }

    @Test
    void everyFilterAppliesToTheSameApplication() throws Exception {
        UUID java = catalog("CANDIDATE_TAG", "Java", true);
        applications.stage(first.applicationId(), ApplicationStage.SCREENING);
        rating(first.applicationId(), 4);
        tagged(first.applicationId(), tag);
        rating(binh.applicationId(), 2);
        tagged(binh.applicationId(), tag);
        tagged(binh.applicationId(), java);

        assertThat(ids(search(hrToken, "stage", "SCREENING"))).containsExactly(first.applicationId());
        assertThat(ids(search(hrToken, "stage", "WITHDRAWN"))).containsExactly(withdrawn.applicationId());
        assertThat(ids(search(hrToken, "stage", "NEW", "stage", "SCREENING"))).hasSize(3);
        assertThat(ids(search(hrToken, "sourceId", linkedIn.toString()))).containsExactly(binh.applicationId());
        assertThat(ids(search(hrToken, "tagId", tag.toString())))
                .containsExactlyInAnyOrder(first.applicationId(), binh.applicationId());
        assertThat(ids(search(hrToken, "tagId", tag.toString(), "tagId", java.toString(), "tagMatch", "ALL")))
                .containsExactly(binh.applicationId());
        assertThat(ids(search(hrToken, "minRating", "3"))).containsExactly(first.applicationId());
        assertThat(ids(search(hrToken, "maxRating", "2"))).containsExactly(binh.applicationId());
        assertThat(item(search(hrToken, "minRating", "4"), first.applicationId()).path("rating").asInt()).isEqualTo(4);
        assertThat(item(search(hrToken, "tagMatch", "ALL", "tagId", java.toString()), binh.applicationId())
                .path("tags").toString()).contains("Java", "Nhãn nội bộ");

        // 00:30 on 10 Oct in Vietnam is still 9 Oct in UTC: the business date decides.
        receivedAt(binh.applicationId(), Instant.parse("2026-10-09T17:30:00Z"));
        assertThat(ids(search(hrToken, "submittedFrom", "2026-10-10", "submittedTo", "2026-10-10")))
                .containsExactly(binh.applicationId());
        assertThat(ids(search(hrToken, "submittedTo", "2026-10-09"))).isEmpty();
        assertThat(ids(search(hrToken, "submittedFrom", "2026-10-11"))).doesNotContain(binh.applicationId()).hasSize(2);

        // The same person: "first" is in SCREENING, "other" is NEW; a keyword and a stage meet on one application.
        assertThat(ids(search(hrToken, "keyword", "nguyen", "stage", "NEW"))).containsExactly(other.applicationId());
        assertThat(ids(search(recruiterToken, "keyword", "nguyen", "stage", "NEW"))).isEmpty();
    }

    @Test
    void invalidCriteriaUnknownCatalogValuesAndPagingAreRejected() throws Exception {
        for (List<String> pairs : List.of(List.of("minRating", "0"), List.of("maxRating", "6"),
                List.of("minRating", "4", "maxRating", "3"), List.of("keyword", "a"),
                List.of("submittedFrom", "2026-10-11", "submittedTo", "2026-10-10"), List.of("size", "101"),
                List.of("page", "-1"), List.of("size", "0"), List.of("stage", "FOO"), List.of("minRating", "x"),
                List.of("submittedFrom", "10/10/2026"), List.of("sourceId", UUID.randomUUID().toString()),
                List.of("sourceId", tag.toString()), List.of("tagId", linkedIn.toString()),
                List.of("tagMatch", "SOME"))) {
            var response = call("GET", SEARCH + query(pairs.toArray(String[]::new)), null, hrToken);
            assertError(response, 400, "VALIDATION_ERROR");
        }
        var fields = json.readTree(call("GET", SEARCH + query("minRating", "0", "keyword", "a"), null, hrToken).body());
        assertThat(fields.path("fieldErrors").has("minRating")).isTrue();
        assertThat(fields.path("fieldErrors").has("keyword")).isTrue();
        assertThat(granted(hr)).isZero();
    }

    @Test
    void pagesAreBoundedAndOrderedByNewestSubmission() throws Exception {
        receivedAt(first.applicationId(), Instant.parse("2026-10-14T03:00:00Z"));
        JsonNode firstPage = search(recruiterToken, "size", "1");
        assertThat(ids(firstPage)).containsExactly(binh.applicationId());
        assertThat(firstPage.path("total").asLong()).isEqualTo(2);
        assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
        JsonNode secondPage = search(recruiterToken, "size", "1", "page", "1");
        assertThat(ids(secondPage)).containsExactly(first.applicationId());
        assertThat(secondPage.path("hasNext").asBoolean()).isFalse();
        assertThat(search(recruiterToken, "page", "1000000").path("items").size()).isZero();
    }
}
