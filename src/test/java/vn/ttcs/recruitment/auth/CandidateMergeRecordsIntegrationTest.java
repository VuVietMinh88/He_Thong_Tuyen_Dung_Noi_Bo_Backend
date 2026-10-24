package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidatemerge.CandidateMergeRecords;
import vn.ttcs.recruitment.candidatemerge.CandidateMergeRecords.CandidateMerge;
import vn.ttcs.recruitment.candidatemerge.CandidateMergeRecords.MovedApplication;
import vn.ttcs.recruitment.candidatemerge.CandidateMergeRecords.Redirect;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 369 (story S5-01, "Lưu dấu vết gộp và dữ liệu phục vụ hoàn tác"): the merge trail of V68_2 through
 * CandidateMergeRecords, and the database rules that keep it append-only except its one undo.
 */
class CandidateMergeRecordsIntegrationTest extends CandidateMergeTestSupport {
    private static final Instant MERGED = ApplicationTestSupport.NOW.plusSeconds(3600);
    private static final Instant DEADLINE = MERGED.plus(Duration.ofHours(24));

    @Autowired CandidateMergeRecords records;

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    private CandidateMerge merge(UUID target, UUID source) {
        return new CandidateMerge(UUID.randomUUID(), target, source, "Cùng số điện thoại và CV", hr, MERGED, DEADLINE,
                null, null);
    }

    @Test
    void aMergeIsRecordedWithTheApplicationsItMovedAndTheCandidatesItRepointed() {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        var hoa = person(requisition, "Đỗ Thị Hoa", "hoa@example.test", "0977111222");
        CandidateMerge merge = merge(first.candidateId(), mai.candidateId());
        Instant linked = ApplicationTestSupport.NOW;
        inTransaction(() -> {
            records.record(merge, List.of(new MovedApplication(mai.applicationId(), linked)),
                    List.of(new Redirect(hoa.candidateId(), linked.plusSeconds(5))));
            return null;
        });

        CandidateMerge found = inTransaction(() -> records.find(merge.id())).orElseThrow();
        assertThat(found).isEqualTo(merge);
        assertThat(found.undone()).isFalse();
        assertThat(found.undoableAt(DEADLINE.minusNanos(1000))).isTrue();
        assertThat(found.undoableAt(DEADLINE)).isFalse();
        assertThat(found.toString()).doesNotContain("Cùng số");
        assertThat(inTransaction(() -> records.movedApplications(merge.id())))
                .containsExactly(new MovedApplication(mai.applicationId(), linked));
        assertThat(inTransaction(() -> records.redirects(merge.id())))
                .containsExactly(new Redirect(hoa.candidateId(), linked.plusSeconds(5)));
        assertThat(inTransaction(() -> records.involving(mai.candidateId(), 10))).containsExactly(merge);
        assertThat(inTransaction(() -> records.involving(first.candidateId(), 10))).containsExactly(merge);
        assertThat(inTransaction(() -> records.involving(hoa.candidateId(), 10))).isEmpty();
        assertThat(inTransaction(() -> records.find(UUID.randomUUID()))).isEmpty();

        // The one undo, then never again.
        Instant undone = MERGED.plusSeconds(60);
        assertThat(inTransaction(() -> records.markUndone(merge.id(), recruiter, undone))).isTrue();
        assertThat(inTransaction(() -> records.markUndone(merge.id(), hr, undone.plusSeconds(1)))).isFalse();
        CandidateMerge after = inTransaction(() -> records.lock(merge.id())).orElseThrow();
        assertThat(after.undoneBy()).isEqualTo(recruiter);
        assertThat(after.undoneAt()).isEqualTo(undone);
        assertThat(after.undoableAt(undone)).isFalse();
        // An undone merge frees its source: it may be merged again.
        inTransaction(() -> {
            records.record(merge(first.candidateId(), mai.candidateId()), List.of(), List.of());
            return null;
        });
        assertThat(inTransaction(() -> records.involving(mai.candidateId(), 1))).hasSize(1);
        assertThat(count("candidate_merges")).isEqualTo(2);
    }

    @Test
    void theTrailIsNeverChangedOrDeletedExceptByItsOneUndo() {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        var hoa = person(requisition, "Đỗ Thị Hoa", "hoa@example.test", "0977111222");
        CandidateMerge merge = merge(first.candidateId(), mai.candidateId());
        inTransaction(() -> {
            records.record(merge, List.of(new MovedApplication(mai.applicationId(), ApplicationTestSupport.NOW)),
                    List.of(new Redirect(hoa.candidateId(), ApplicationTestSupport.NOW)));
            return null;
        });
        for (String change : List.of("UPDATE candidate_merges SET reason = 'Đổi lý do'",
                "UPDATE candidate_merges SET undo_deadline = undo_deadline + interval '1 day'",
                "UPDATE candidate_merges SET target_candidate_id = '" + hoa.candidateId() + "'",
                "DELETE FROM candidate_merges",
                "UPDATE candidate_merge_applications SET linked_at = now()",
                "DELETE FROM candidate_merge_applications",
                "UPDATE candidate_merge_redirects SET merged_at = now()",
                "DELETE FROM candidate_merge_redirects")) {
            assertThatThrownBy(() -> jdbc.update(change)).as(change)
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("append-only");
        }
        // The undo may not change anything else at the same time, and an undo is final.
        assertThatThrownBy(() -> jdbc.update("UPDATE candidate_merges SET undone_by = ?, undone_at = ?, reason = NULL",
                hr, Timestamp.from(MERGED.plusSeconds(1)))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE candidate_merges SET undone_by = ?, undone_at = ?", hr, Timestamp.from(MERGED.plusSeconds(1)));
        assertThatThrownBy(() -> jdbc.update("UPDATE candidate_merges SET undone_at = undone_at + interval '1 second'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE candidate_merges SET undone_by = NULL, undone_at = NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("candidate_merges")).isEqualTo(1);
        assertThat(count("candidate_merge_applications")).isEqualTo(1);
        assertThat(count("candidate_merge_redirects")).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesInconsistentMergesAndMergeStates() {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        UUID target = first.candidateId(), source = mai.candidateId();
        String insert = "INSERT INTO candidate_merges (id, target_candidate_id, source_candidate_id, reason, merged_by,"
                + " merged_at, undo_deadline, undone_by, undone_at) VALUES (?,?,?,?,?,?,?,?,?)";
        Timestamp merged = Timestamp.from(MERGED), deadline = Timestamp.from(DEADLINE);
        List<Object[]> refused = List.of(
                new Object[]{UUID.randomUUID(), target, target, null, hr, merged, deadline, null, null},
                new Object[]{UUID.randomUUID(), target, source, " \t", hr, merged, deadline, null, null},
                new Object[]{UUID.randomUUID(), target, source, null, hr, merged, merged, null, null},
                new Object[]{UUID.randomUUID(), target, source, null, hr, merged, deadline, hr, null},
                new Object[]{UUID.randomUUID(), target, source, null, hr, merged, deadline, null, merged},
                new Object[]{UUID.randomUUID(), target, source, null, hr, merged, deadline, hr, deadline},
                new Object[]{UUID.randomUUID(), target, source, null, hr, merged, deadline, hr,
                        Timestamp.from(MERGED.minusSeconds(1))},
                new Object[]{UUID.randomUUID(), target, UUID.randomUUID(), null, hr, merged, deadline, null, null},
                new Object[]{UUID.randomUUID(), target, source, null, UUID.randomUUID(), merged, deadline, null, null});
        for (Object[] row : refused) {
            assertThatThrownBy(() -> jdbc.update(insert, row)).isInstanceOf(DataIntegrityViolationException.class);
        }
        // A source is merged away once at a time.
        jdbc.update(insert, UUID.randomUUID(), target, source, null, hr, merged, deadline, null, null);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), target, source, null, hr, merged, deadline,
                null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("candidate_merges")).isEqualTo(1);

        // A merged candidate names a real other candidate, with the time, never itself.
        assertThatThrownBy(() -> jdbc.update("UPDATE candidates SET merged_into = id, merged_at = ? WHERE id = ?",
                merged, source)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE candidates SET merged_into = ? WHERE id = ?", target, source))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE candidates SET merged_into = ?, merged_at = ? WHERE id = ?",
                UUID.randomUUID(), merged, source)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("UPDATE candidates SET merged_into = ?, merged_at = ? WHERE id = ?", target, merged,
                source)).isEqualTo(1);
        // The target cannot go away while a candidate points at it or a merge names it.
        jdbc.update("DELETE FROM candidate_applications WHERE candidate_id = ?", target);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM candidates WHERE id = ?", target))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theRecordsRefuseAMergeThatCouldNotBeUndoneExactly() {
        var mai = person(requisition, "Trần Thị Mai", "mai@example.test", "0912345678");
        assertThatThrownBy(() -> new CandidateMerge(UUID.randomUUID(), first.candidateId(), first.candidateId(), null, hr,
                MERGED, DEADLINE, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidateMerge(UUID.randomUUID(), first.candidateId(), mai.candidateId(), null, hr,
                MERGED, DEADLINE, hr, null)).isInstanceOf(IllegalArgumentException.class);
        CandidateMerge merge = merge(first.candidateId(), mai.candidateId());
        MovedApplication moved = new MovedApplication(mai.applicationId(), ApplicationTestSupport.NOW);
        // A @Repository's IllegalArgumentException reaches the caller translated by Spring.
        assertThatThrownBy(() -> inTransaction(() -> {
            records.record(merge, List.of(moved, moved), List.of());
            return null;
        })).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("each application");
        // Outside a transaction nothing is written.
        assertThatThrownBy(() -> records.record(merge, List.of(moved), List.of()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(count("candidate_merges")).isZero();
    }
}
