package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 368 (story S5-01, "Hiển thị so sánh cạnh nhau trước khi gộp; gộp giữ lại toàn bộ lịch sử ứng tuyển của cả hai
 * hồ sơ"): the side by side preview and the merge of one candidate into another, over HTTP on real PostgreSQL.
 */
class CandidateMergeIntegrationTest extends CandidateMergeTestSupport {
    @Test
    void thePreviewShowsBothCandidatesSideBySide() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "+84 912 345 678");
        JsonNode preview = ok(previewOf(first.candidateId(), mai.candidateId(), hrToken));
        JsonNode target = preview.path("target");
        JsonNode source = preview.path("source");
        assertThat(target.path("candidateId").asText()).isEqualTo(first.candidateId().toString());
        assertThat(applicationIds(target.path("applications")))
                .containsExactlyInAnyOrder(first.applicationId().toString(), other.applicationId().toString());
        assertThat(texts(target.path("emails"))).containsExactly("lan@example.test");
        assertThat(source.path("candidateId").asText()).isEqualTo(mai.candidateId().toString());
        assertThat(texts(source.path("fullNames"))).containsExactly("Trần Thị Mai");
        assertThat(texts(source.path("phones"))).hasSize(1);
        JsonNode maiApplication = source.path("applications").get(0);
        assertThat(maiApplication.path("applicationId").asText()).isEqualTo(mai.applicationId().toString());
        assertThat(maiApplication.path("postingTitle").asText()).isEqualTo("Java");
        assertThat(maiApplication.path("stageName").asText()).isEqualTo("Hồ sơ mới");
        assertThat(maiApplication.path("sameRequisition").asBoolean()).isTrue();
        assertThat(texts(preview.path("matchedBy"))).containsExactly("PHONE");
        assertThat(texts(preview.path("sharedRequisitionIds"))).containsExactly(requisition.toString());
        assertThat(preview.path("mergeable").asBoolean()).isTrue();
        assertThat(preview.path("blockers").isEmpty()).isTrue();
        // One granted read per application shown, on its own side.
        assertThat(granted(hr)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND candidate_id = ?",
                Long.class, hr, mai.candidateId())).isEqualTo(1);
        // A preview changes nothing.
        assertThat(linkOf(mai.applicationId())).isEqualTo(mai.candidateId());
        assertThat(count("candidate_merges")).isZero();
    }

    @Test
    void aRecruiterSeesItsOwnApplicationsAndThatItCannotMergeTheOthers() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        // RECRUITER reads first only (other is in OUTSIDER's requisition), so it may not move lan's profile.
        JsonNode preview = ok(previewOf(first.candidateId(), mai.candidateId(), recruiterToken));
        assertThat(applicationIds(preview.path("target").path("applications")))
                .containsExactly(first.applicationId().toString());
        assertThat(preview.path("mergeable").asBoolean()).isFalse();
        assertThat(texts(preview.path("blockers"))).containsExactly("NOT_WRITABLE");
        assertThat(mergeOf(first.candidateId(), mai.candidateId(), recruiterToken).statusCode()).isEqualTo(403);
        assertThat(mergeOf(mai.candidateId(), first.candidateId(), recruiterToken).statusCode()).isEqualTo(403);
        assertThat(count("candidate_merges")).isZero();

        // Two people in its own requisition: a recruiter of that requisition merges them.
        var hoa = person(requisition, "Đỗ Thị Hoa", "hoa@example.test", "0912345678");
        JsonNode merged = ok(mergeOf(mai.candidateId(), hoa.candidateId(), recruiterToken));
        assertThat(merged.path("targetCandidateId").asText()).isEqualTo(mai.candidateId().toString());
        assertThat(linkOf(hoa.applicationId())).isEqualTo(mai.candidateId());
        // The hiring manager reads the department's candidates but writes none of them.
        assertThat(mergeOf(first.candidateId(), mai.candidateId(), token(manager)).statusCode()).isEqualTo(403);
    }

    @Test
    void aMergeMovesTheLinksKeepsEveryApplicationAndRecordsTheTrail() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        jdbc.update("UPDATE job_applications SET stage = 'SCREENING' WHERE id = ?", mai.applicationId());
        long applicationsBefore = count("job_applications");
        // An hour later, with a token of that time.
        clock.set(ApplicationTestSupport.NOW.plusSeconds(3600));
        hrToken = token(hr);

        String body = "{\"sourceCandidateId\":\"" + mai.candidateId() + "\",\"reason\":\"  Cùng số điện thoại, đã gọi xác nhận. \"}";
        JsonNode merged = ok(call("POST", candidatePath(first.candidateId()) + "/merge", body, hrToken));
        var at = ApplicationTestSupport.NOW.plusSeconds(3600);
        assertThat(merged.path("sourceCandidateId").asText()).isEqualTo(mai.candidateId().toString());
        assertThat(merged.path("mergedAt").asText()).isEqualTo(at.toString());
        assertThat(merged.path("undoDeadline").asText()).isEqualTo(at.plusSeconds(86400).toString());
        assertThat(texts(merged.path("movedApplicationIds"))).containsExactly(mai.applicationId().toString());
        assertThat(merged.path("redirectedCandidateIds").isEmpty()).isTrue();

        // Every application, stage and history row stays; only the link moved.
        assertThat(count("job_applications")).isEqualTo(applicationsBefore);
        assertThat(linkOf(mai.applicationId())).isEqualTo(first.candidateId());
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class,
                mai.applicationId())).isEqualTo("SCREENING");
        assertThat(mergedInto(mai.candidateId())).isEqualTo(first.candidateId());
        UUID mergeId = UUID.fromString(merged.path("mergeId").asText());
        assertThat(jdbc.queryForObject("SELECT reason FROM candidate_merges WHERE id = ?", String.class, mergeId))
                .isEqualTo("Cùng số điện thoại, đã gọi xác nhận.");
        assertThat(jdbc.queryForObject("SELECT merged_by FROM candidate_merges WHERE id = ?", UUID.class, mergeId))
                .isEqualTo(hr);
        assertThat(jdbc.queryForObject("SELECT linked_at FROM candidate_merge_applications WHERE merge_id = ?",
                Timestamp.class, mergeId).toInstant()).isEqualTo(ApplicationTestSupport.NOW);

        // The merged profile is gone from the suggestions and its preview says where it went.
        assertThat(duplicates(first.candidateId(), hrToken).isEmpty()).isTrue();
        JsonNode after = ok(previewOf(first.candidateId(), mai.candidateId(), hrToken));
        assertThat(after.path("source").path("mergedInto").asText()).isEqualTo(first.candidateId().toString());
        assertThat(after.path("source").path("applications").isEmpty()).isTrue();
        assertThat(texts(after.path("blockers"))).containsExactly("SOURCE_MERGED");
        assertError(mergeOf(first.candidateId(), mai.candidateId(), hrToken), 409, "CANDIDATE_ALREADY_MERGED");
        assertError(mergeOf(mai.candidateId(), first.candidateId(), hrToken), 409, "CANDIDATE_ALREADY_MERGED");

        // A later application with the merged email joins the profile it went into.
        var again = person(otherRequisition, "Trần Thị Mai", "MAI@example.test", "0912345678");
        assertThat(again.candidateId()).isEqualTo(first.candidateId());
        assertThat(linkOf(again.applicationId())).isEqualTo(first.candidateId());
    }

    @Test
    void mergingATargetOfEarlierMergesRepointsThemSoEveryLinkIsOneHop() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        var hoa = person(requisition, "Đỗ Thị Hoa", "hoa@example.test", "0912345678");
        ok(mergeOf(mai.candidateId(), hoa.candidateId(), hrToken));
        clock.set(ApplicationTestSupport.NOW.plusSeconds(60));
        JsonNode merged = ok(mergeOf(first.candidateId(), mai.candidateId(), hrToken));
        assertThat(texts(merged.path("redirectedCandidateIds"))).containsExactly(hoa.candidateId().toString());
        assertThat(texts(merged.path("movedApplicationIds")))
                .containsExactlyInAnyOrder(mai.applicationId().toString(), hoa.applicationId().toString());
        assertThat(mergedInto(hoa.candidateId())).isEqualTo(first.candidateId());
        assertThat(mergedInto(mai.candidateId())).isEqualTo(first.candidateId());
        // hoa keeps the time of its own merge; the redirect remembers it for an undo.
        UUID mergeId = UUID.fromString(merged.path("mergeId").asText());
        assertThat(jdbc.queryForObject("SELECT merged_at FROM candidate_merge_redirects WHERE merge_id = ? AND candidate_id = ?",
                Timestamp.class, mergeId, hoa.candidateId()).toInstant()).isEqualTo(ApplicationTestSupport.NOW);
        var later = person(otherRequisition, "Đỗ Thị Hoa", "hoa@example.test", "0977000111");
        assertThat(later.candidateId()).isEqualTo(first.candidateId());
    }

    @Test
    void aSourceHoldingAnActiveOfferOrTalentPoolEntryStays() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        offer(mai.applicationId(), "PENDING_HR", ApplicationTestSupport.NOW);
        JsonNode preview = ok(previewOf(first.candidateId(), mai.candidateId(), hrToken));
        assertThat(texts(preview.path("blockers"))).containsExactly("SOURCE_ACTIVE_OFFER");
        assertError(mergeOf(first.candidateId(), mai.candidateId(), hrToken), 409, "CANDIDATE_MERGE_ACTIVE_OFFER");
        // The other way round is allowed: the offer's candidate stays the profile that remains.
        ok(mergeOf(mai.candidateId(), first.candidateId(), hrToken));
        assertThat(linkOf(first.applicationId())).isEqualTo(mai.candidateId());

        var hoa = person(otherRequisition, "Đỗ Thị Hoa", "hoa@example.test", "0912345678");
        jdbc.update("UPDATE job_applications SET stage = 'REJECTED' WHERE id = ?", hoa.applicationId());
        poolEntry(hoa.applicationId(), ApplicationTestSupport.NOW);
        assertThat(texts(ok(previewOf(mai.candidateId(), hoa.candidateId(), hrToken)).path("blockers")))
                .containsExactly("SOURCE_IN_TALENT_POOL");
        assertError(mergeOf(mai.candidateId(), hoa.candidateId(), hrToken), 409, "CANDIDATE_MERGE_TALENT_POOL");
        // A removed entry no longer holds the profile.
        jdbc.update("UPDATE talent_pool_entries SET removed_by = ?, removed_at = ?", hr,
                Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(1)));
        ok(mergeOf(mai.candidateId(), hoa.candidateId(), hrToken));
        assertThat(count("candidate_merges")).isEqualTo(2);
    }

    @Test
    void invalidRequestsUnknownCandidatesAndUnreadableSidesAreRefused() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        assertError(mergeOf(mai.candidateId(), mai.candidateId(), hrToken), 400, "CANDIDATE_MERGE_SAME_CANDIDATE");
        assertError(previewOf(mai.candidateId(), mai.candidateId(), hrToken), 400, "CANDIDATE_MERGE_SAME_CANDIDATE");
        assertError(call("GET", candidatePath(mai.candidateId()) + "/merge-preview", null, hrToken), 400, "VALIDATION_ERROR");
        assertError(call("POST", candidatePath(mai.candidateId()) + "/merge", "{}", hrToken), 400, "VALIDATION_ERROR");
        assertError(call("POST", candidatePath(mai.candidateId()) + "/merge",
                "{\"sourceCandidateId\":\"" + first.candidateId() + "\",\"actorId\":\"" + hr + "\"}", hrToken), 400, "INVALID_JSON");
        assertError(call("POST", candidatePath(mai.candidateId()) + "/merge",
                "{\"sourceCandidateId\":\"" + first.candidateId() + "\",\"reason\":\"<b>gộp</b>\"}", hrToken), 400, "VALIDATION_ERROR");
        assertError(mergeOf(mai.candidateId(), UUID.randomUUID(), hrToken), 404, "CANDIDATE_NOT_FOUND");
        assertError(mergeOf(UUID.randomUUID(), mai.candidateId(), hrToken), 404, "CANDIDATE_NOT_FOUND");
        assertError(previewOf(UUID.randomUUID(), mai.candidateId(), hrToken), 404, "CANDIDATE_NOT_FOUND");
        String noRole = token(applications.account("Không vai trò", "none@example.test"));
        assertThat(previewOf(first.candidateId(), mai.candidateId(), noRole).statusCode()).isEqualTo(403);
        assertThat(mergeOf(first.candidateId(), mai.candidateId(), noRole).statusCode()).isEqualTo(403);
        assertThat(mergeOf(first.candidateId(), mai.candidateId(), null).statusCode()).isEqualTo(401);
        assertThat(count("candidate_access_events")).isZero();

        // tuan is in OUTSIDER's requisition only: RECRUITER's preview is refused and logged for tuan alone.
        var tuan = person(otherRequisition, "Phạm Văn Tuấn", "tuan@example.test", "0912345678");
        assertThat(previewOf(first.candidateId(), tuan.candidateId(), recruiterToken).statusCode()).isEqualTo(403);
        assertThat(denied(recruiter)).isEqualTo(1);
        assertThat(granted(recruiter)).isZero();
        assertThat(jdbc.queryForObject("SELECT candidate_id FROM candidate_access_events WHERE actor_id = ?", UUID.class,
                recruiter)).isEqualTo(tuan.candidateId());
        assertThat(count("candidate_merges")).isZero();
    }
}
