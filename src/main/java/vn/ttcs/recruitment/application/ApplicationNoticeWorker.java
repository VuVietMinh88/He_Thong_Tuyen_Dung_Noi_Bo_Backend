package vn.ttcs.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.MailFailureClassifier;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 346 (story S4-06): delivers the emails queued by a withdrawal (V35), like TRAN DUC LUONG's
 * ApplicationConfirmationWorker: at most 10 claims per run, SMTP outside any database transaction, every result fenced
 * by the lease, a refused recipient is a permanent RECIPIENT_REFUSED. Each mail is rendered at send time, in its own
 * read-only transaction, from the application, its WITHDRAWN event and the recipient's account as they are then:
 * <ul>
 * <li>CANDIDATE_WITHDRAWAL: to the application's address; an application that is no longer WITHDRAWN is OBSOLETE and
 * nothing is sent.</li>
 * <li>RECRUITER_WITHDRAWAL (a batch of one recipient and requisition): the recipient must still be enabled, not
 * locked, on that requisition's team (PRIMARY or SUPPORTING) and hold a CANDIDATES read code, else the whole batch is
 * RECIPIENT_UNAVAILABLE and nothing is sent; each application no longer WITHDRAWN is OBSOLETE and left out; no
 * application left, no mail.</li>
 * </ul>
 */
@Component
public class ApplicationNoticeWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationNoticeWorker.class);
    private final ApplicationNoticeRepository outbox;
    private final ApplicationWithdrawalNotices notices;
    private final ApplicationNoticeMailSender mail;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;

    ApplicationNoticeWorker(ApplicationNoticeRepository outbox, ApplicationWithdrawalNotices notices,
            ApplicationNoticeMailSender mail, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.outbox = outbox; this.notices = notices; this.mail = mail; this.jdbc = jdbc;
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(5);
    }

    @Scheduled(initialDelayString = "${app.application-notice.poll-delay-ms:10000}",
            fixedDelayString = "${app.application-notice.poll-delay-ms:10000}")
    public void poll() {
        try {
            for (int i = 0; i < 10 && processNext(); i++) { /* bounded batch; no SMTP inside a database transaction */ }
        } catch (RuntimeException exception) {
            LOG.warn("Application notice processing failed; will retry on the next run");
        }
    }

    // One claim: false when nothing is due. A failure of the database leaves the rows leased; they are claimed again
    // when the lease expires.
    public boolean processNext() {
        var claimed = outbox.claim();
        if (claimed.isEmpty()) return false;
        var batch = claimed.get();
        // Exhaustive without default: a new kind does not compile until someone decides how it is rendered.
        switch (batch.kind()) {
            case CANDIDATE_WITHDRAWAL -> candidate(batch);
            case RECRUITER_WITHDRAWAL -> recruiter(batch);
        }
        return true;
    }

    private void candidate(ApplicationNoticeRepository.Batch batch) {
        var notice = read(() -> outbox.candidateNotice(batch.applicationId()));
        if (notice.isEmpty()) {
            outbox.abandon(batch, batch.ids(), "OBSOLETE");
            return;
        }
        deliver(batch, batch.ids(), () -> mail.sendCandidate(notice.get(), batch.messageId()));
    }

    private void recruiter(ApplicationNoticeRepository.Batch batch) {
        var rendered = read(() -> {
            var recipient = outbox.requisitionOf(batch.applicationId())
                    .flatMap(requisition -> outbox.teamMember(batch.recipientId(), requisition))
                    .filter(member -> notices.mayReadCandidates(batch.recipientId()));
            return recipient.map(member -> new Rendered(member, outbox.withdrawnItems(batch.applicationIds())));
        });
        if (rendered.isEmpty()) {
            outbox.abandon(batch, batch.ids(), "RECIPIENT_UNAVAILABLE");
            return;
        }
        var withdrawn = new HashSet<UUID>();
        rendered.get().items().forEach(item -> withdrawn.add(item.applicationId()));
        var included = batch.items().stream().filter(item -> withdrawn.contains(item.applicationId()))
                .map(ApplicationNoticeRepository.Item::id).toList();
        var obsolete = batch.items().stream().filter(item -> !withdrawn.contains(item.applicationId()))
                .map(ApplicationNoticeRepository.Item::id).toList();
        outbox.abandon(batch, obsolete, "OBSOLETE");
        if (included.isEmpty()) return;
        deliver(batch, included,
                () -> mail.sendRecruiter(rendered.get().recipient(), rendered.get().items(), batch.messageId()));
    }

    private void deliver(ApplicationNoticeRepository.Batch batch, List<UUID> ids, Runnable send) {
        if (!outbox.renew(batch, ids)) return;
        try { send.run(); }
        catch (MailException exception) {
            outbox.failed(batch, ids, MailFailureClassifier.refusedRecipient(exception), "RECIPIENT_REFUSED");
            return;
        }
        // Fenced by lease_token: a late worker cannot overwrite a newer owner's result.
        outbox.sent(batch, ids);
    }

    private <T> T read(Supplier<T> query) {
        return Objects.requireNonNull(reads.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout='3s'");
            return query.get();
        }));
    }

    private record Rendered(ApplicationNoticeRepository.Recipient recipient,
                            List<ApplicationNoticeRepository.WithdrawnItem> items) { }
}
