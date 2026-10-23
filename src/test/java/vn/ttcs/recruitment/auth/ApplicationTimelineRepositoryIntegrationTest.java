package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Actor;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.ActorType;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.EmailSent;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.MailKind;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.RatingSnapshot;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Recipient;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.RecipientType;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.ScreeningNote;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.StageChange;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Submission;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Type;
import vn.ttcs.recruitment.timeline.ApplicationTimelinePage;
import vn.ttcs.recruitment.timeline.ApplicationTimelineRepository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.application.ApplicationStage.INTERVIEW;
import static vn.ttcs.recruitment.application.ApplicationStage.NEW;
import static vn.ttcs.recruitment.application.ApplicationStage.OFFER;
import static vn.ttcs.recruitment.application.ApplicationStage.REJECTED;
import static vn.ttcs.recruitment.application.ApplicationStage.SCREENING;
import static vn.ttcs.recruitment.application.ApplicationStage.WITHDRAWN;
import static vn.ttcs.recruitment.auth.ApplicationTestSupport.NOW;

/**
 * Task 403 (story S5-07): ApplicationTimelineRepository on the migrated schema (embedded PostgreSQL): every source of
 * one application, newest first with a stable tie order, pages that agree with their total, only mails the SMTP server
 * accepted, no secret or email, and never an event of another application of the same candidate profile. Reads run in
 * a REPEATABLE READ transaction. The API also writes an access audit in its transaction.
 */
class ApplicationTimelineRepositoryIntegrationTest extends ScreeningTestSupport {
    private static final String STAFF_NOTE = "GHI_CHU_NOI_BO_CHUYEN_GIAI_DOAN";
    private static final String WITHDRAWAL_REASON = "LY_DO_RUT_CUA_UNG_VIEN";
    private static final String SCREENING_NOTE = "GHI_CHU_SANG_LOC_NOI_BO";
    private static final String OTHER_NOTE = "GHI_CHU_CUA_HO_SO_KHAC";
    private static final String MESSAGE_ID = "timeline-secret-message-id";
    private static final String KEY_ID = "timeline-key-1";
    private static final Comparator<ApplicationTimelineEvent> NEWEST_FIRST = Comparator
            .comparing(ApplicationTimelineEvent::occurredAt).reversed().thenComparing(ApplicationTimelineEvent::id);
    @Autowired ApplicationTimelineRepository timeline;
    ApplicationTimelineFixtures events;

    @BeforeEach
    void timelineFixtures() {
        events = new ApplicationTimelineFixtures(jdbc);
    }

    @Test
    void mixedSourcesAreNewestFirstWithStableTiesAndEveryPageAgreesWithItsTotal() {
        UUID app = first.applicationId();
        Instant tie = NOW.plus(Duration.ofMinutes(20));
        UUID mail = events.sentConfirmation(app, message(1), NOW.plusSeconds(30));
        UUID screening = events.staffStage(app, NEW, SCREENING, recruiter, STAFF_NOTE, NOW.plus(Duration.ofMinutes(10)));
        UUID noteA = events.note(app, hr, "Ghi chú A", tie);
        UUID noteB = events.note(app, recruiter, "Ghi chú B", tie);
        UUID interview = events.staffStage(app, SCREENING, INTERVIEW, hr, null, tie);
        events.rate(app, 4, hr, NOW.plus(Duration.ofMinutes(30)));
        var expected = new ArrayList<>(List.of("submission:" + app, "email:" + mail, "stage:" + screening,
                "note:" + noteA, "note:" + noteB, "stage:" + interview, "rating:" + app));
        var times = Map.of("submission:" + app, received(app), "email:" + mail, NOW.plusSeconds(30),
                "stage:" + screening, NOW.plus(Duration.ofMinutes(10)), "note:" + noteA, tie, "note:" + noteB, tie,
                "stage:" + interview, tie, "rating:" + app, NOW.plus(Duration.ofMinutes(30)));
        // Newest first; equal times by id, byte by byte (ids are ASCII, so String order is the "C" collation's).
        expected.sort(Comparator.comparing((String id) -> times.get(id)).reversed().thenComparing(Comparator.naturalOrder()));

        var full = read(app, 0, 100);
        assertThat(full.totalElements()).isEqualTo(7);
        assertThat(full.items()).extracting(ApplicationTimelineEvent::id).containsExactlyElementsOf(expected)
                .doesNotHaveDuplicates();
        assertThat(full.items()).isSortedAccordingTo(NEWEST_FIRST);
        assertThat(full.items()).allSatisfy(event -> {
            assertThat(event.applicationId()).isEqualTo(app);
            assertThat(event.occurredAt()).isEqualTo(times.get(event.id()));
        });
        assertThat(full.items()).extracting(ApplicationTimelineEvent::type).containsExactly(
                Type.SCREENING_RATING_SNAPSHOT, Type.SCREENING_NOTE_ADDED, Type.SCREENING_NOTE_ADDED,
                Type.STAGE_CHANGED, Type.STAGE_CHANGED, Type.EMAIL_SENT, Type.APPLICATION_SUBMITTED);

        for (int size : new int[]{1, 2, 3}) {
            var pages = new ArrayList<String>();
            for (int page = 0; page * size < 7; page++) {
                var slice = read(app, page, size);
                assertThat(slice.totalElements()).isEqualTo(7);
                assertThat(slice.page()).isEqualTo(page);
                assertThat(slice.size()).isEqualTo(size);
                assertThat(slice.items()).hasSize(Math.min(size, 7 - page * size));
                slice.items().forEach(event -> pages.add(event.id()));
            }
            assertThat(pages).as("pages of %d", size).containsExactlyElementsOf(expected);
        }
        var pastTheEnd = read(app, 3, 3);
        assertThat(pastTheEnd.items()).isEmpty();
        assertThat(pastTheEnd.totalElements()).isEqualTo(7);
    }

    @Test
    void submissionIsTheCandidatesWithThePostingTitleAndNoPersonalData() {
        UUID app = first.applicationId();
        var page = read(app, 0, 20);
        assertThat(page.totalElements()).isEqualTo(1);
        var submitted = page.items().getFirst();
        assertThat(submitted.id()).isEqualTo("submission:" + app);
        assertThat(submitted.type()).isEqualTo(Type.APPLICATION_SUBMITTED);
        assertThat(submitted.applicationId()).isEqualTo(app);
        assertThat(submitted.occurredAt()).isEqualTo(received(app)).isEqualTo(NOW);
        assertThat(submitted.actor()).isEqualTo(new Actor(ActorType.CANDIDATE, null, null));
        assertThat(submitted.details()).isEqualTo(new Submission(jdbc.queryForObject(
                "SELECT posting_title FROM job_applications WHERE id = ?", String.class, app)));
        assertThat(((Submission) submitted.details()).postingTitle()).isEqualTo("Java");
        assertThat(json.writeValueAsString(page)).doesNotContain("@example.test", ApplicationTestSupport.FULL_NAME,
                ApplicationTestSupport.PHONE, first.trackingCode(), first.candidateId().toString());
    }

    @Test
    void referredSubmissionNamesTheStaffSubmitterWithoutReadingTheReferralNote() {
        UUID app = first.applicationId();
        String privateNote = "REFERRAL_PRIVATE_NOTE";
        jdbc.update("INSERT INTO application_referrals(application_id,referrer_id,note,consent_declared_at) VALUES (?,?,?,?)",
                app, recruiter, privateNote, Timestamp.from(NOW));
        jdbc.update("UPDATE user_accounts SET full_name=? WHERE id=?", "Người giới thiệu hiện tại", recruiter);
        var page = read(app, 0, 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().actor()).isEqualTo(
                new Actor(ActorType.STAFF, recruiter, "Người giới thiệu hiện tại"));
        assertThat(json.writeValueAsString(page)).doesNotContain(privateNote, "@example.test", first.trackingCode());
        assertThat(read(other.applicationId(), 0, 20).items().getFirst().actor())
                .isEqualTo(new Actor(ActorType.CANDIDATE, null, null));
    }

    @Test
    void stageChangesKeepTheirDetailsIncludingTheWithdrawalAndNameTheStaffActorAsItIsNow() {
        UUID app = first.applicationId();
        UUID screening = events.staffStage(app, NEW, SCREENING, recruiter, STAFF_NOTE, NOW.plus(Duration.ofHours(1)));
        UUID skip = events.staffStage(app, SCREENING, OFFER, hr, null, NOW.plus(Duration.ofHours(2)));
        UUID withdrawal = events.withdrawal(app, OFFER, WITHDRAWAL_REASON, NOW.plus(Duration.ofHours(3)));
        UUID reason = catalog("REJECTION_REASON", "Không phù hợp", true);
        UUID rejection = events.rejection(other.applicationId(), NEW, hr, reason, "Không phù hợp",
                NOW.plus(Duration.ofHours(1)));
        // Names as they are now: the account's current name, but the rejection reason's name as stored (V40).
        jdbc.update("UPDATE user_accounts SET full_name = 'Recruiter Đổi Tên' WHERE id = ?", recruiter);
        jdbc.update("UPDATE recruitment_catalog_items SET name = 'Tên mới' WHERE id = ?", reason);

        var page = read(app, 0, 20);
        var items = page.items();
        assertThat(items).extracting(ApplicationTimelineEvent::id).containsExactly("stage:" + withdrawal,
                "stage:" + skip, "stage:" + screening, "submission:" + app);
        assertThat(items.subList(0, 3)).extracting(ApplicationTimelineEvent::type).containsOnly(Type.STAGE_CHANGED);
        assertThat(items.get(0).occurredAt()).isEqualTo(NOW.plus(Duration.ofHours(3)));
        assertThat(items.get(0).actor()).isEqualTo(new Actor(ActorType.CANDIDATE, null, null));
        assertThat(items.get(0).details()).isEqualTo(new StageChange(OFFER, WITHDRAWN, WITHDRAWAL_REASON, null, null, false));
        assertThat(items.get(1).actor()).isEqualTo(new Actor(ActorType.STAFF, hr, "HR Nội Bộ"));
        assertThat(items.get(1).details()).isEqualTo(new StageChange(SCREENING, OFFER, null, null, null, true));
        assertThat(items.get(2).occurredAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(items.get(2).actor()).isEqualTo(new Actor(ActorType.STAFF, recruiter, "Recruiter Đổi Tên"));
        assertThat(items.get(2).details()).isEqualTo(new StageChange(NEW, SCREENING, STAFF_NOTE, null, null, false));

        var rejected = read(other.applicationId(), 0, 20).items();
        assertThat(rejected).extracting(ApplicationTimelineEvent::id).containsExactly("stage:" + rejection,
                "submission:" + other.applicationId());
        assertThat(rejected.getFirst().details()).isEqualTo(
                new StageChange(NEW, REJECTED, null, reason, "Không phù hợp", false));

        // Staff may read the internal text through the API, but no log line built from toString may carry it.
        var logged = page + " " + items + " " + items.stream().map(event -> event.details() + " " + event.actor()).toList();
        assertThat(logged).doesNotContain(STAFF_NOTE, WITHDRAWAL_REASON, "Recruiter Đổi Tên", "HR Nội Bộ");
        assertThat(json.writeValueAsString(page)).contains(STAFF_NOTE, WITHDRAWAL_REASON).doesNotContain("@example.test");
    }

    @Test
    void onlyMailsTheSmtpServerAcceptedAppearAndOnlyAsMetadata() {
        UUID app = first.applicationId();
        UUID confirmation = events.sentConfirmation(app, message(1), NOW.plusSeconds(5));
        UUID candidateNotice = events.notice(app, "CANDIDATE_WITHDRAWAL", null, "SENT", message(2),
                NOW.plus(Duration.ofHours(1)));
        events.notice(app, "RECRUITER_WITHDRAWAL", recruiter, "FAILED", message(3), NOW.plus(Duration.ofHours(2)));
        var own = read(app, 0, 20);
        assertThat(own.items()).extracting(ApplicationTimelineEvent::id).containsExactly("notice:" + candidateNotice,
                "email:" + confirmation, "submission:" + app);
        assertThat(own.items().subList(0, 2)).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo(Type.EMAIL_SENT);
            assertThat(event.actor()).isEqualTo(new Actor(ActorType.SYSTEM, null, null));
        });
        var toCandidate = new Recipient(RecipientType.CANDIDATE, null, null);
        assertThat(own.items().get(0).occurredAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(own.items().get(0).details()).isEqualTo(new EmailSent(MailKind.CANDIDATE_WITHDRAWAL, toCandidate));
        assertThat(own.items().get(1).occurredAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(own.items().get(1).details()).isEqualTo(new EmailSent(MailKind.APPLICATION_CONFIRMATION, toCandidate));

        UUID recruiterNotice = events.notice(other.applicationId(), "RECRUITER_WITHDRAWAL", outsider, "SENT", message(4),
                NOW.plus(Duration.ofHours(2)));
        events.unsentConfirmation(other.applicationId(), "RETRY_WAIT", message(5), KEY_ID, NOW);
        var theirs = read(other.applicationId(), 0, 20);
        assertThat(theirs.items()).extracting(ApplicationTimelineEvent::id).containsExactly("notice:" + recruiterNotice,
                "submission:" + other.applicationId());
        assertThat(theirs.items().getFirst().details()).isEqualTo(new EmailSent(MailKind.RECRUITER_WITHDRAWAL,
                new Recipient(RecipientType.STAFF, outsider, "Recruiter B")));

        UUID posting = applications.publishedPosting("Mail");
        var bodies = new StringBuilder(json.writeValueAsString(own)).append(json.writeValueAsString(theirs));
        int number = 10;
        for (String status : List.of("PENDING", "PROCESSING", "RETRY_WAIT", "FAILED")) {
            UUID waiting = applications.stored(posting, status.toLowerCase(Locale.ROOT) + "@example.test").applicationId();
            events.unsentConfirmation(waiting, status, message(number++), KEY_ID, NOW);
            events.notice(waiting, "CANDIDATE_WITHDRAWAL", null, status, message(number++), NOW);
            var unsent = read(waiting, 0, 20);
            assertThat(unsent.items()).as(status).extracting(ApplicationTimelineEvent::type)
                    .containsExactly(Type.APPLICATION_SUBMITTED);
            bodies.append(json.writeValueAsString(unsent));
        }
        // The unsent rows are really there, payload and all: the timeline filtered them out.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE status <> 'SENT' AND ciphertext IS NOT NULL",
                Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_notice_outbox WHERE status <> 'SENT'",
                Integer.class)).isEqualTo(5);
        assertThat(bodies.toString()).doesNotContain(MESSAGE_ID, KEY_ID, ApplicationTimelineFixtures.CIPHERTEXT,
                Base64.getEncoder().encodeToString(ApplicationTimelineFixtures.CIPHERTEXT.getBytes(StandardCharsets.US_ASCII)),
                "@example.test", "lease", "attempt", "failure");
    }

    @Test
    void applicationsOfTheSameCandidateProfileNeverShareEvents() {
        UUID app = first.applicationId(), otherApp = other.applicationId();
        assertThat(other.candidateId()).isEqualTo(first.candidateId());
        UUID otherNote = events.note(otherApp, hr, OTHER_NOTE, NOW.plus(Duration.ofMinutes(1)));
        events.rate(otherApp, 1, hr, NOW.plus(Duration.ofMinutes(2)));
        UUID otherStage = events.staffStage(otherApp, NEW, SCREENING, outsider, null, NOW.plus(Duration.ofMinutes(3)));
        UUID otherMail = events.sentConfirmation(otherApp, message(1), NOW.plus(Duration.ofMinutes(4)));
        UUID otherNotice = events.notice(otherApp, "CANDIDATE_WITHDRAWAL", null, "SENT", message(2),
                NOW.plus(Duration.ofMinutes(6)));
        UUID ownNote = events.note(app, recruiter, SCREENING_NOTE, NOW.plus(Duration.ofMinutes(5)));

        var own = read(app, 0, 20);
        assertThat(own.items()).extracting(ApplicationTimelineEvent::id).containsExactly("note:" + ownNote,
                "submission:" + app);
        assertThat(own.items()).allSatisfy(event -> assertThat(event.applicationId()).isEqualTo(app));
        assertThat(json.writeValueAsString(own)).doesNotContain(otherApp.toString(), otherNote.toString(), OTHER_NOTE,
                outsider.toString());

        var theirs = read(otherApp, 0, 20);
        assertThat(theirs.totalElements()).isEqualTo(6);
        assertThat(theirs.items()).extracting(ApplicationTimelineEvent::id).containsExactly("notice:" + otherNotice,
                "email:" + otherMail, "stage:" + otherStage, "rating:" + otherApp, "note:" + otherNote,
                "submission:" + otherApp);
        assertThat(theirs.items()).allSatisfy(event -> assertThat(event.applicationId()).isEqualTo(otherApp));
        assertThat(json.writeValueAsString(theirs)).doesNotContain(app.toString(), ownNote.toString(), SCREENING_NOTE);
    }

    @Test
    void ratingIsOnlyTheCurrentSnapshotNotesAreEventsAndTagsAreNot() {
        UUID app = first.applicationId();
        UUID older = events.note(app, recruiter, "Ghi chú đầu", NOW.plus(Duration.ofMinutes(30)));
        events.rate(app, 2, recruiter, NOW.plus(Duration.ofHours(1)));
        events.rate(app, 5, hr, NOW.plus(Duration.ofHours(2)));
        jdbc.update("INSERT INTO application_screening_tags(application_id,tag_id,added_by,added_at) VALUES (?,?,?,?)",
                app, tag, hr, Timestamp.from(NOW.plus(Duration.ofHours(3))));
        UUID newer = events.note(app, hr, SCREENING_NOTE, NOW.plus(Duration.ofHours(4)));

        var page = read(app, 0, 20);
        var items = page.items();
        assertThat(items).extracting(ApplicationTimelineEvent::id).containsExactly("note:" + newer, "rating:" + app,
                "note:" + older, "submission:" + app);
        var rating = items.get(1);
        assertThat(rating.type()).isEqualTo(Type.SCREENING_RATING_SNAPSHOT);
        assertThat(rating.occurredAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(rating.actor()).isEqualTo(new Actor(ActorType.STAFF, hr, "HR Nội Bộ"));
        assertThat(rating.details()).isEqualTo(new RatingSnapshot(5));
        assertThat(items).filteredOn(event -> event.type() == Type.SCREENING_RATING_SNAPSHOT).hasSize(1);

        assertThat(items.get(0).type()).isEqualTo(Type.SCREENING_NOTE_ADDED);
        assertThat(items.get(0).occurredAt()).isEqualTo(NOW.plus(Duration.ofHours(4)));
        assertThat(items.get(0).actor()).isEqualTo(new Actor(ActorType.STAFF, hr, "HR Nội Bộ"));
        assertThat(items.get(0).details()).isEqualTo(new ScreeningNote(SCREENING_NOTE));
        assertThat(items.get(2).actor()).isEqualTo(new Actor(ActorType.STAFF, recruiter, "Recruiter A"));
        assertThat(items.get(2).details()).isEqualTo(new ScreeningNote("Ghi chú đầu"));

        assertThat(json.writeValueAsString(page)).doesNotContain(tag.toString(), "Nhãn nội bộ");
        assertThat(page + " " + items + " " + items.stream().map(event -> event.details().toString()).toList())
                .doesNotContain(SCREENING_NOTE, "Ghi chú đầu", "value=5");
    }

    @Test
    void readsNeedTheCallersTransactionAndStayWithinTheirBounds() {
        UUID app = first.applicationId();
        assertThatThrownBy(() -> timeline.find(app, 0, 20)).isInstanceOf(IllegalTransactionStateException.class);
        for (int[] invalid : new int[][]{{-1, 20}, {ApplicationTimelineRepository.MAX_PAGE + 1, 20}, {0, 0}, {0, -1},
                {0, ApplicationTimelineRepository.MAX_SIZE + 1}}) {
            // Spring's repository proxy translates this invalid call, preserving the validation cause.
            assertThatThrownBy(() -> read(app, invalid[0], invalid[1]))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> read(null, 0, 20)).isInstanceOf(NullPointerException.class);

        var last = read(app, ApplicationTimelineRepository.MAX_PAGE, ApplicationTimelineRepository.MAX_SIZE);
        assertThat(last.items()).isEmpty();
        assertThat(last.totalElements()).isEqualTo(1);
        assertThat(last.page()).isEqualTo(ApplicationTimelineRepository.MAX_PAGE);
        assertThat(last.size()).isEqualTo(ApplicationTimelineRepository.MAX_SIZE);
        assertThat(read(app, 0, ApplicationTimelineRepository.MAX_SIZE).items()).hasSize(1);

        UUID unknown = UUID.randomUUID();
        var none = read(unknown, 0, 20);
        assertThat(none.applicationId()).isEqualTo(unknown);
        assertThat(none.items()).isEmpty();
        assertThat(none.totalElements()).isZero();

        // The page keeps its own copy of the events.
        var source = new ArrayList<>(read(app, 0, 20).items());
        var copy = new ApplicationTimelinePage(app, source, 1, 0, 20);
        source.clear();
        assertThat(copy.items()).hasSize(1);
        assertThatThrownBy(() -> copy.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private ApplicationTimelinePage read(UUID application, int page, int size) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        return transaction.execute(status -> timeline.find(application, page, size));
    }

    private Instant received(UUID application) {
        return jdbc.queryForObject("SELECT received_at FROM job_applications WHERE id = ?", Timestamp.class, application)
                .toInstant();
    }

    private static String message(int number) {
        return "<" + MESSAGE_ID + "-" + number + "@example.test>";
    }
}
