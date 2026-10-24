package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 367 (story S5-01, "Phát hiện trùng theo email và số điện thoại khi có hồ sơ mới"): the candidates that share an
 * effective email or phone with one candidate's readable applications, over HTTP on real PostgreSQL.
 */
class CandidateDuplicateIntegrationTest extends CandidateMergeTestSupport {
    @Test
    void aSharedPhoneInItsInternationalFormSuggestsTheOtherCandidate() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "+84 912 345 678");
        var bao = person(requisition, "Lý Văn Bảo", "bao@example.test", "0988777666");
        assertThat(mai.candidateId()).isNotEqualTo(first.candidateId());

        JsonNode response = ok(duplicatesOf(first.candidateId(), recruiterToken));
        assertThat(response.path("candidateId").asText()).isEqualTo(first.candidateId().toString());
        assertThat(response.path("truncated").asBoolean()).isFalse();
        JsonNode duplicates = response.path("duplicates");
        assertThat(candidates(duplicates)).containsExactly(mai.candidateId().toString());
        JsonNode duplicate = duplicates.get(0);
        assertThat(texts(duplicate.path("matchedBy"))).containsExactly("PHONE");
        assertThat(duplicate.path("moreApplications").asBoolean()).isFalse();
        JsonNode application = duplicate.path("applications").get(0);
        assertThat(application.path("applicationId").asText()).isEqualTo(mai.applicationId().toString());
        assertThat(application.path("requisitionId").asText()).isEqualTo(requisition.toString());
        assertThat(application.path("postingTitle").asText()).isEqualTo("Java");
        assertThat(application.path("fullName").asText()).isEqualTo("Trần Thị Mai");
        assertThat(application.path("stage").asText()).isEqualTo("NEW");
        assertThat(application.path("stageName").asText()).isEqualTo("Hồ sơ mới");
        assertThat(application.path("receivedAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(texts(application.path("matchedBy"))).containsExactly("PHONE");
        // No contact data, no other person and none of the candidate's own applications.
        assertThat(response.toString()).doesNotContain(bao.applicationId().toString(), first.applicationId().toString(),
                "mai@example.test", "912345678", "trackingCode");
        // One granted read per listed application, for the listed candidate only.
        assertThat(granted(recruiter)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT candidate_id FROM candidate_access_events WHERE actor_id = ?", UUID.class,
                recruiter)).isEqualTo(mai.candidateId());

        // The same comparison from the other side, where HR reads both of lan's applications.
        JsonNode back = duplicates(mai.candidateId(), hrToken);
        assertThat(candidates(back)).containsExactly(first.candidateId().toString());
        assertThat(applicationIds(back.get(0).path("applications")))
                .containsExactlyInAnyOrder(first.applicationId().toString(), other.applicationId().toString());
    }

    @Test
    void aConfirmedContactReplacesTheSubmittedOneOnBothSides() throws Exception {
        var hoa = person(requisition, "Đỗ Thị Hoa", "hoa@example.test", "0977111222");
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        assertThat(candidates(duplicates(first.candidateId(), recruiterToken))).containsExactly(mai.candidateId().toString());

        // A recruiter confirmed lan's email for hoa's application: it now matches by email, typed in another case.
        confirm(hoa.applicationId(), "LAN@example.test", "0977111222");
        JsonNode both = duplicates(first.candidateId(), recruiterToken);
        assertThat(candidates(both)).containsExactlyInAnyOrder(hoa.candidateId().toString(), mai.candidateId().toString());
        for (JsonNode duplicate : both) {
            String expected = duplicate.path("candidateId").asText().equals(hoa.candidateId().toString()) ? "EMAIL" : "PHONE";
            assertThat(texts(duplicate.path("matchedBy"))).containsExactly(expected);
        }

        // The confirmed phone of first replaces its submitted one: for RECRUITER, who reads first only, mai no longer
        // matches; HR also reads other, whose submitted phone still is mai's.
        confirm(first.applicationId(), "lan@example.test", "0966000111");
        assertThat(candidates(duplicates(first.candidateId(), recruiterToken))).containsExactly(hoa.candidateId().toString());
        assertThat(candidates(duplicates(first.candidateId(), hrToken)))
                .containsExactlyInAnyOrder(hoa.candidateId().toString(), mai.candidateId().toString());

        // Both an email and a phone: one candidate, both reasons, on the application and on the candidate.
        confirm(mai.applicationId(), "lan@example.test", "0912345678");
        JsonNode mixed = duplicates(first.candidateId(), hrToken);
        JsonNode maiDuplicate = mixed.get(candidates(mixed).indexOf(mai.candidateId().toString()));
        assertThat(texts(maiDuplicate.path("matchedBy"))).containsExactly("EMAIL", "PHONE");
        assertThat(texts(maiDuplicate.path("applications").get(0).path("matchedBy"))).containsExactly("EMAIL", "PHONE");
    }

    @Test
    void onlyApplicationsTheReaderMayReadAreComparedAndListed() throws Exception {
        // tuan shares lan's phone, in OUTSIDER's requisition only.
        var tuan = person(otherRequisition, "Phạm Văn Tuấn", "tuan@example.test", "0912345678");
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        assertThat(candidates(duplicates(first.candidateId(), recruiterToken))).containsExactly(mai.candidateId().toString());
        assertThat(candidates(duplicates(first.candidateId(), token(manager)))).containsExactly(mai.candidateId().toString());
        assertThat(candidates(duplicates(first.candidateId(), hrToken)))
                .containsExactlyInAnyOrder(mai.candidateId().toString(), tuan.candidateId().toString());
        // OUTSIDER reads only other (lan's second application): its keys find tuan, never mai.
        assertThat(candidates(duplicates(first.candidateId(), token(outsider)))).containsExactly(tuan.candidateId().toString());

        // A candidate with no readable application: 403, logged once without an application.
        long before = granted(recruiter);
        assertThat(duplicatesOf(tuan.candidateId(), recruiterToken).statusCode()).isEqualTo(403);
        assertThat(denied(recruiter)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'DENIED'"
                + " AND candidate_id = ? AND application_id IS NULL AND access_kind = 'PROFILE'", Long.class, recruiter,
                tuan.candidateId())).isEqualTo(1);
        assertThat(granted(recruiter)).isEqualTo(before);
    }

    @Test
    void unknownCandidatesAndCallersWithoutTheReadPermissionAreRefusedBeforeAnyLookup() throws Exception {
        assertError(duplicatesOf(UUID.randomUUID(), hrToken), 404, "CANDIDATE_NOT_FOUND");
        assertError(duplicatesOf(UUID.randomUUID(), recruiterToken), 404, "CANDIDATE_NOT_FOUND");
        String noRole = token(applications.account("Không vai trò", "none@example.test"));
        assertThat(duplicatesOf(first.candidateId(), noRole).statusCode()).isEqualTo(403);
        assertThat(duplicatesOf(UUID.randomUUID(), noRole).statusCode()).isEqualTo(403);
        assertThat(duplicatesOf(first.candidateId(), null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/candidates/not-a-uuid/duplicates", null, hrToken).statusCode()).isEqualTo(400);
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void anApplicationReceivedAMomentAgoIsComparedOnTheNextRead() throws Exception {
        JsonNode none = ok(duplicatesOf(first.candidateId(), recruiterToken));
        assertThat(none.path("duplicates").isEmpty()).isTrue();
        assertThat(none.path("truncated").asBoolean()).isFalse();
        assertThat(count("candidate_access_events")).isZero();

        clock.set(ApplicationTestSupport.NOW.plusSeconds(60));
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912-345-678");
        assertThat(candidates(duplicates(first.candidateId(), recruiterToken))).containsExactly(mai.candidateId().toString());
    }

    @Test
    void theListIsCutAtTwentyCandidatesAndTenApplicationsEach() throws Exception {
        for (int index = 0; index < 21; index++) {
            clock.set(ApplicationTestSupport.NOW.plusSeconds(index + 1));
            person(requisition, "Người " + index, "person" + index + "@example.test", "0912345678");
        }
        // The most recent match: eleven applications of one person in eleven requisitions.
        UUID busy = null;
        for (int index = 0; index < 11; index++) {
            clock.set(ApplicationTestSupport.NOW.plusSeconds(100 + index));
            UUID posting = applications.publishedPosting("Vị trí " + index);
            busy = applications.stored(posting, new vn.ttcs.recruitment.application.ApplicationSubmission("Bùi Văn Hải",
                    "hai@example.test", "0912345678", null, null)).candidateId();
        }
        JsonNode response = ok(duplicatesOf(first.candidateId(), hrToken));
        assertThat(response.path("truncated").asBoolean()).isTrue();
        JsonNode duplicates = response.path("duplicates");
        assertThat(duplicates.size()).isEqualTo(20);
        assertThat(duplicates.get(0).path("candidateId").asText()).isEqualTo(busy.toString());
        assertThat(duplicates.get(0).path("applications").size()).isEqualTo(10);
        assertThat(duplicates.get(0).path("moreApplications").asBoolean()).isTrue();
        // Newest first: the oldest of the 21 single applications (person0) is the one left out.
        assertThat(duplicates.toString()).doesNotContain("Người 0\"");
        assertThat(duplicates.get(1).path("applications").get(0).path("fullName").asText()).isEqualTo("Người 20");
        assertThat(granted(hr)).isEqualTo(10 + 19);
    }
}
