package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 370 (story S5-01, "Gộp nhầm có thể hoàn tác trong 24 giờ"): undoing a merge within its deadline, the refusals
 * after it or after related changes, and the merge history of a candidate, over HTTP on real PostgreSQL.
 */
class CandidateMergeUndoIntegrationTest extends CandidateMergeTestSupport {
    private static final Instant MERGED = ApplicationTestSupport.NOW.plusSeconds(60);

    @Test
    void anUndoPutsEveryLinkBackIncludingApplicationsThatArrivedThroughTheSourceEmail() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        clock.set(MERGED);
        UUID merge = merged(first.candidateId(), mai.candidateId());
        // An application with mai's email arrives after the merge and joins lan's profile.
        clock.set(MERGED.plusSeconds(60));
        var later = person(otherRequisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        assertThat(later.candidateId()).isEqualTo(first.candidateId());

        clock.set(MERGED.plusSeconds(120));
        JsonNode undone = ok(undoOf(merge, token(hr)));
        assertThat(undone.path("mergeId").asText()).isEqualTo(merge.toString());
        assertThat(undone.path("undoneAt").asText()).isEqualTo(MERGED.plusSeconds(120).toString());
        assertThat(texts(undone.path("restoredApplicationIds")))
                .containsExactlyInAnyOrder(mai.applicationId().toString(), later.applicationId().toString());
        assertThat(undone.path("restoredCandidateIds").isEmpty()).isTrue();

        assertThat(linkOf(mai.applicationId())).isEqualTo(mai.candidateId());
        assertThat(linkOf(later.applicationId())).isEqualTo(mai.candidateId());
        assertThat(linkOf(first.applicationId())).isEqualTo(first.candidateId());
        assertThat(linkOf(other.applicationId())).isEqualTo(first.candidateId());
        // The moved application gets its old link time back; the later one keeps its own.
        assertThat(jdbc.queryForObject("SELECT linked_at FROM candidate_applications WHERE application_id = ?",
                Timestamp.class, mai.applicationId()).toInstant()).isEqualTo(ApplicationTestSupport.NOW);
        assertThat(jdbc.queryForObject("SELECT linked_at FROM candidate_applications WHERE application_id = ?",
                Timestamp.class, later.applicationId()).toInstant()).isEqualTo(MERGED.plusSeconds(60));
        assertThat(mergedInto(mai.candidateId())).isNull();
        assertThat(jdbc.queryForObject("SELECT undone_by FROM candidate_merges WHERE id = ?", UUID.class, merge))
                .isEqualTo(hr);
        // mai is a duplicate suggestion again, and a new application with her email joins her own profile.
        assertThat(candidates(duplicates(first.candidateId(), token(hr)))).containsExactly(mai.candidateId().toString());
        UUID accounting = applications.publishedPosting("Kế toán");
        assertThat(applications.stored(accounting, new ApplicationSubmission("Trần Thị Mai", "MAI@example.test",
                "0912345678", null, null)).candidateId()).isEqualTo(mai.candidateId());

        assertError(undoOf(merge, token(hr)), 409, "CANDIDATE_MERGE_ALREADY_UNDONE");
        // Undone, mai may be merged again.
        UUID again = merged(first.candidateId(), mai.candidateId());
        assertThat(again).isNotEqualTo(merge);
    }

    @Test
    void anUndoRestoresTheCandidatesTheMergeRepointed() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        var hoa = person(requisition, "Đỗ Thị Hoa", "hoa@example.test", "0912345678");
        UUID hoaIntoMai = merged(mai.candidateId(), hoa.candidateId());
        clock.set(MERGED);
        UUID maiIntoLan = merged(first.candidateId(), mai.candidateId());
        assertThat(mergedInto(hoa.candidateId())).isEqualTo(first.candidateId());

        // The older merge cannot be undone while its target is merged into lan.
        assertError(undoOf(hoaIntoMai, token(hr)), 409, "CANDIDATE_MERGE_CHANGED");

        JsonNode undone = ok(undoOf(maiIntoLan, token(hr)));
        assertThat(texts(undone.path("restoredCandidateIds"))).containsExactly(hoa.candidateId().toString());
        assertThat(mergedInto(hoa.candidateId())).isEqualTo(mai.candidateId());
        assertThat(jdbc.queryForObject("SELECT merged_at FROM candidates WHERE id = ?", Timestamp.class,
                hoa.candidateId()).toInstant()).isEqualTo(ApplicationTestSupport.NOW);
        assertThat(linkOf(hoa.applicationId())).isEqualTo(mai.candidateId());
        assertThat(linkOf(mai.applicationId())).isEqualTo(mai.candidateId());
        assertThat(person(otherRequisition, "Đỗ Thị Hoa", "hoa@example.test", "0977000111").candidateId())
                .isEqualTo(mai.candidateId());

        // Now the older merge can be undone too: hoa is a profile of her own again.
        ok(undoOf(hoaIntoMai, token(hr)));
        assertThat(mergedInto(hoa.candidateId())).isNull();
        assertThat(linkOf(hoa.applicationId())).isEqualTo(hoa.candidateId());
    }

    @Test
    void anUndoIsRefusedAtTheDeadline() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        var hoa = person(otherRequisition, "Đỗ Thị Hoa", "hoa@example.test", "0912345678");
        clock.set(MERGED);
        UUID maiMerge = merged(first.candidateId(), mai.candidateId());
        UUID hoaMerge = merged(first.candidateId(), hoa.candidateId());
        clock.set(MERGED.plusSeconds(86400 - 1));
        ok(undoOf(maiMerge, token(hr)));
        clock.set(MERGED.plusSeconds(86400));
        assertError(undoOf(hoaMerge, token(hr)), 409, "CANDIDATE_MERGE_UNDO_EXPIRED");
        assertThat(linkOf(hoa.applicationId())).isEqualTo(first.candidateId());
        JsonNode history = ok(historyOf(first.candidateId(), token(hr))).path("merges");
        assertThat(history.size()).isEqualTo(2);
        for (JsonNode item : history) assertThat(item.path("undoable").asBoolean()).isFalse();
    }

    @Test
    void anOfferOrTalentPoolEntryCreatedSinceForAnApplicationThatWouldGoBackBlocksTheUndo() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        var hoa = person(otherRequisition, "Đỗ Thị Hoa", "hoa@example.test", "0912345678");
        clock.set(MERGED);
        UUID maiMerge = merged(first.candidateId(), mai.candidateId());
        UUID hoaMerge = merged(first.candidateId(), hoa.candidateId());
        // An offer on one of lan's own applications changes nothing for the undo.
        offer(first.applicationId(), "PENDING_HR", MERGED.plusSeconds(10));
        offer(mai.applicationId(), "CANCELLED", MERGED.plusSeconds(20));
        jdbc.update("UPDATE job_applications SET stage = 'REJECTED' WHERE id = ?", hoa.applicationId());
        poolEntry(hoa.applicationId(), MERGED.plusSeconds(30));
        clock.set(MERGED.plusSeconds(60));
        assertError(undoOf(maiMerge, token(hr)), 409, "CANDIDATE_MERGE_CHANGED");
        assertError(undoOf(hoaMerge, token(hr)), 409, "CANDIDATE_MERGE_CHANGED");
        assertThat(linkOf(mai.applicationId())).isEqualTo(first.candidateId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merges WHERE undone_at IS NOT NULL", Long.class))
                .isZero();
    }

    @Test
    void theUndoIsAWriteOverTheTargetsApplications() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        var hoa = person(requisition, "Đỗ Thị Hoa", "hoa@example.test", "0912345678");
        UUID merge = UUID.fromString(ok(mergeOf(mai.candidateId(), hoa.candidateId(), recruiterToken))
                .path("mergeId").asText());
        assertThat(undoOf(merge, token(outsider)).statusCode()).isEqualTo(403);
        assertThat(undoOf(merge, token(manager)).statusCode()).isEqualTo(403);
        String noRole = token(applications.account("Không vai trò", "none@example.test"));
        assertThat(undoOf(merge, noRole).statusCode()).isEqualTo(403);
        assertThat(undoOf(merge, null).statusCode()).isEqualTo(401);
        assertError(undoOf(UUID.randomUUID(), hrToken), 404, "CANDIDATE_MERGE_NOT_FOUND");
        assertThat(call("POST", "/api/v1/candidate-merges/not-a-uuid/undo", null, hrToken).statusCode()).isEqualTo(400);
        assertThat(linkOf(hoa.applicationId())).isEqualTo(mai.candidateId());

        JsonNode undone = ok(undoOf(merge, recruiterToken));
        assertThat(texts(undone.path("restoredApplicationIds"))).containsExactly(hoa.applicationId().toString());
        assertThat(jdbc.queryForObject("SELECT undone_by FROM candidate_merges WHERE id = ?", UUID.class, merge))
                .isEqualTo(recruiter);
    }

    @Test
    void theHistoryListsTheMergesOfACandidateAsReadersMayReadIt() throws Exception {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        clock.set(MERGED);
        String body = "{\"sourceCandidateId\":\"" + mai.candidateId() + "\",\"reason\":\"Cùng một người\"}";
        UUID merge = UUID.fromString(ok(call("POST", candidatePath(first.candidateId()) + "/merge", body, token(hr)))
                .path("mergeId").asText());

        JsonNode history = ok(historyOf(first.candidateId(), token(hr)));
        assertThat(history.path("truncated").asBoolean()).isFalse();
        JsonNode item = history.path("merges").get(0);
        assertThat(item.path("mergeId").asText()).isEqualTo(merge.toString());
        assertThat(item.path("role").asText()).isEqualTo("TARGET");
        assertThat(item.path("sourceCandidateId").asText()).isEqualTo(mai.candidateId().toString());
        assertThat(item.path("reason").asText()).isEqualTo("Cùng một người");
        assertThat(item.path("mergedBy").path("fullName").asText()).isEqualTo("HR Nội Bộ");
        assertThat(item.path("mergedAt").asText()).isEqualTo(MERGED.toString());
        assertThat(item.path("undoDeadline").asText()).isEqualTo(MERGED.plusSeconds(86400).toString());
        assertThat(item.path("movedApplications").asInt()).isEqualTo(1);
        assertThat(item.path("undoable").asBoolean()).isTrue();
        assertThat(item.path("undoneBy").isNull() || item.path("undoneBy").isMissingNode()).isTrue();
        // The merged source has no application of its own: it is read through lan's, here by RECRUITER.
        JsonNode sourceSide = ok(historyOf(mai.candidateId(), token(recruiter))).path("merges").get(0);
        assertThat(sourceSide.path("role").asText()).isEqualTo("SOURCE");
        // No application data is shown, so nothing is logged as granted.
        assertThat(granted(hr)).isZero();
        assertThat(granted(recruiter)).isZero();

        ok(undoOf(merge, token(hr)));
        JsonNode after = ok(historyOf(mai.candidateId(), token(hr))).path("merges").get(0);
        assertThat(after.path("undoneBy").path("id").asText()).isEqualTo(hr.toString());
        assertThat(after.path("undoable").asBoolean()).isFalse();

        // tuan is in OUTSIDER's requisition only: RECRUITER is refused (logged), an unknown id is 404.
        var tuan = person(otherRequisition, "Phạm Văn Tuấn", "tuan@example.test", "0900000000");
        assertThat(historyOf(tuan.candidateId(), token(recruiter)).statusCode()).isEqualTo(403);
        assertThat(denied(recruiter)).isEqualTo(1);
        assertError(historyOf(UUID.randomUUID(), token(hr)), 404, "CANDIDATE_NOT_FOUND");
        assertThat(ok(historyOf(tuan.candidateId(), token(hr))).path("merges").isEmpty()).isTrue();
    }
}
