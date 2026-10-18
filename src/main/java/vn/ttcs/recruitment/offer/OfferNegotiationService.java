package vn.ttcs.recruitment.offer;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.offerdelivery.OfferOutboxRepository;
import vn.ttcs.recruitment.position.SalaryBandService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Negotiation records never rewrite a candidate response; a changed offer always starts a fresh approval round. */
@Service
public class OfferNegotiationService {
    private final JdbcTemplate jdbc;
    private final OfferService offers;
    private final OfferRepository data;
    private final OfferAccess access;
    private final SalaryBandService bands;
    private final OfferOutboxRepository outbox;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public OfferNegotiationService(JdbcTemplate jdbc, OfferService offers, OfferRepository data, OfferAccess access,
                                   SalaryBandService bands, OfferOutboxRepository outbox, Clock clock,
                                   PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.offers = offers;
        this.data = data;
        this.access = access;
        this.bands = bands;
        this.outbox = outbox;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public OfferNegotiationView add(Jwt jwt, UUID id, OfferNegotiationRequests.Note request) {
        return offers.execute(writes, () -> {
            var locked = lock(jwt, id);
            OfferService.version(locked.offer(), request.expectedVersion());
            requireSource(locked.offer(), locked.context());
            bands.standardBand(locked.offer().positionId());
            access.requireWriteScope(jwt, locked.writer(), locked.offer().applicationId());
            String reason = OfferDeclineReason.parseOptional(request.reason()).name();
            UUID noteId = UUID.randomUUID();
            Instant at = now();
            jdbc.update("""
                    INSERT INTO offer_negotiations(id,offer_id,reason,comment,actor_id,actor_name,occurred_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, noteId, id, reason, request.comment(), locked.writer().id(), locked.writer().name(), Timestamp.from(at));
            // A note does not change the approved terms or invalidate an email already queued for this version.
            data.event(locked.offer(), "NEGOTIATION_RECORDED", locked.writer(), request.comment(), at, false);
            access.audit(locked.writer(), locked.context());
            return new OfferNegotiationView(noteId, id, reason, request.comment(), locked.writer().id(), locked.writer().name(), at);
        });
    }

    public OfferNegotiationView.Page history(Jwt jwt, UUID id, int page, int size) {
        return offers.execute(reads, () -> {
            var actor = access.reader(jwt);
            var offer = offers.readable(actor, id);
            OfferService.paging(page, size);
            var items = jdbc.query("SELECT * FROM offer_negotiations WHERE offer_id=? ORDER BY sequence_number LIMIT ? OFFSET ?",
                    this::note, id, size, (long) page * size);
            long total = jdbc.queryForObject("SELECT count(*) FROM offer_negotiations WHERE offer_id=?", Long.class, id);
            access.audit(actor.getId(), access.context(offer.applicationId()));
            return new OfferNegotiationView.Page(items, page, size, total, (total + size - 1) / size);
        });
    }

    public OfferView revise(Jwt jwt, UUID id, OfferNegotiationRequests.Revision request) {
        return offers.execute(writes, () -> {
            var locked = lock(jwt, id);
            var previous = locked.offer();
            OfferService.version(previous, request.expectedVersion());
            requireSource(previous, locked.context());
            if (previous.revisionNumber() == Integer.MAX_VALUE) {
                throw conflict("OFFER_REVISION_LIMIT", "Offer đã đạt giới hạn số phiên bản.");
            }
            var band = bands.standardBand(previous.positionId());
            access.requireWriteScope(jwt, locked.writer(), previous.applicationId());
            offers.terms(request.salary(), request.explanation(), request.startDate(), request.responseDeadline(), band);
            Instant at = now();
            UUID successor = UUID.randomUUID();

            // Reopen only this candidate's exact decline, never a staff rejection or withdrawal.
            if ("DECLINED".equals(previous.status())) reopen(previous, locked.writer(), successor, at);
            data.transition(id, "SUPERSEDED", at);
            jdbc.update("UPDATE offer_response_tokens SET revoked_at=? WHERE offer_id=? AND revoked_at IS NULL", Timestamp.from(at), id);
            outbox.cancelPending(id);
            data.createRevision(successor, locked.context(), request.draft(), band, locked.writer().id(), at,
                    id, previous.revisionNumber() + 1);

            // A candidate may have another application's offer in flight; the unique index can wait for it.
            access.requireWriteScope(jwt, locked.writer(), previous.applicationId());
            offers.terms(request.salary(), request.explanation(), request.startDate(), request.responseDeadline(), band);
            var created = offers.find(successor, false);
            data.event(offers.find(id, false), "SUPERSEDED", locked.writer(), request.comment(), at, false);
            data.event(created, "REVISION_CREATED", locked.writer(), request.comment(), at, true);
            access.audit(locked.writer(), locked.context());
            return offers.mask(created, locked.writer().id());
        });
    }

    public OfferNegotiationView.Suggestion suggestion(Jwt jwt, UUID id) {
        return offers.execute(reads, () -> {
            var actor = access.reader(jwt);
            var offer = offers.find(id, false);
            access.scope(actor.getId(), offer.applicationId(), false);
            var context = access.context(offer.applicationId());
            String eligibility = suggestionReason(offer, context);
            String declineReason = offer.declineReason();
            String comment = offer.responseNote();
            String source = declineReason == null ? null : "CANDIDATE";
            if (declineReason == null) {
                var latest = jdbc.query("SELECT * FROM offer_negotiations WHERE offer_id=? ORDER BY sequence_number DESC LIMIT 1",
                        this::note, id).stream().findFirst();
                if (latest.isPresent()) {
                    declineReason = latest.get().reason();
                    comment = latest.get().comment();
                    source = "STAFF";
                }
            }
            access.audit(actor.getId(), context);
            return new OfferNegotiationView.Suggestion(id, offer.applicationId(), offer.candidateId(),
                    "ELIGIBLE_DECLINED".equals(eligibility), eligibility, declineReason, comment, source);
        });
    }

    private Locked lock(Jwt jwt, UUID id) {
        var actor = access.lockWriter(jwt);
        access.requireWriterRole(actor.id(), actor.roles());
        var initial = offers.find(id, false);
        access.requireWriteScope(jwt, actor, initial.applicationId());
        var context = access.lockParents(initial.applicationId());
        var offer = offers.find(id, true);
        access.requireWriteScope(jwt, actor, offer.applicationId());
        OfferService.sameIdentity(offer, context);
        return new Locked(actor, context, offer);
    }

    private void requireSource(OfferView offer, OfferAccess.Context context) {
        if (!OfferNegotiationPolicy.canRevise(offer.status()) || offer.replacedByOfferId() != null) {
            throw conflict("OFFER_NOT_REVISABLE", "Chỉ thương lượng hoặc thay thế offer đã duyệt, đã gửi hoặc ứng viên vừa từ chối.");
        }
        if (!"APPROVED".equals(context.requisitionStatus())) {
            throw conflict("OFFER_APPLICATION_INACTIVE", "Yêu cầu tuyển dụng không còn hoạt động.");
        }
        if ("DECLINED".equals(offer.status())) {
            if (!"REJECTED".equals(context.stage()) || !currentDecline(offer)) {
                throw conflict("OFFER_DECLINE_CONTEXT_CHANGED", "Hồ sơ không còn ở lần từ chối offer này.");
            }
        } else {
            access.requireActive(context);
        }
    }

    private void reopen(OfferView previous, OfferAccess.Writer writer, UUID successor, Instant at) {
        if (jdbc.update("UPDATE job_applications SET stage='OFFER' WHERE id=? AND stage='REJECTED'", previous.applicationId()) != 1) {
            throw conflict("OFFER_DECLINE_CONTEXT_CHANGED", "Hồ sơ không còn ở lần từ chối offer này.");
        }
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,reason,occurred_at)
                VALUES (?,?,'REJECTED','OFFER','STAFF',?,?,?)
                """, UUID.randomUUID(), previous.applicationId(), writer.id(),
                "Mở lại để thương lượng offer phiên bản " + successor, Timestamp.from(at));
    }

    private String suggestionReason(OfferView offer, OfferAccess.Context context) {
        if (!"DECLINED".equals(offer.status()) || offer.replacedByOfferId() != null) return "NOT_CURRENT_DECLINED";
        if (!offer.candidateId().equals(context.candidateId()) || !offer.positionId().equals(context.positionId())
                || !offer.email().equals(context.email()) || !offer.candidateName().equals(context.candidateName())
                || !"APPROVED".equals(context.requisitionStatus()) || !"REJECTED".equals(context.stage())) {
            return "OFFER_CONTEXT_CHANGED";
        }
        boolean activePosition = Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM positions WHERE id=?", Boolean.class, offer.positionId()));
        if (!activePosition || !currentDecline(offer)) return "DECLINE_NOT_CURRENT";
        boolean effective = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM offers WHERE candidate_id=? AND status IN
                    ('DRAFT','CHANGES_REQUESTED','PENDING_HR','PENDING_HIGHER','APPROVED','SENT','ACCEPTED'))
                """, Boolean.class, offer.candidateId()));
        if (effective) return "CANDIDATE_HAS_EFFECTIVE_OFFER";
        // Ignore earlier revisions in this chain, even if the test clock or imported timestamps are identical.
        boolean latest = Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH RECURSIVE ancestors AS (
                    SELECT id,previous_offer_id FROM offers WHERE id=?
                    UNION ALL SELECT o.id,o.previous_offer_id FROM offers o JOIN ancestors a ON o.id=a.previous_offer_id
                )
                SELECT NOT EXISTS(SELECT 1 FROM offers o WHERE o.candidate_id=? AND o.created_at>=?
                    AND o.id NOT IN (SELECT id FROM ancestors))
                """, Boolean.class, offer.id(), offer.candidateId(), Timestamp.from(offer.createdAt())));
        return latest ? "ELIGIBLE_DECLINED" : "NEWER_OFFER_EXISTS";
    }

    private boolean currentDecline(OfferView offer) {
        if (offer.respondedAt() == null) return false;
        // No arbitrary UUID tie-break: a second event at the same instant makes provenance ambiguous.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM application_stage_events e
                    WHERE e.application_id=? AND e.offer_id=? AND e.actor_type='CANDIDATE'
                    AND e.stage_before='OFFER' AND e.stage_after='REJECTED' AND e.occurred_at=?
                    AND NOT EXISTS(SELECT 1 FROM application_stage_events later WHERE later.application_id=e.application_id
                        AND later.id<>e.id AND later.occurred_at>=e.occurred_at))
                """, Boolean.class, offer.applicationId(), offer.id(), Timestamp.from(offer.respondedAt())));
    }

    private OfferNegotiationView note(ResultSet row, int index) throws SQLException {
        return new OfferNegotiationView(row.getObject("id", UUID.class), row.getObject("offer_id", UUID.class),
                row.getString("reason"), row.getString("comment"), row.getObject("actor_id", UUID.class),
                row.getString("actor_name"), row.getTimestamp("occurred_at").toInstant());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private record Locked(OfferAccess.Writer writer, OfferAccess.Context context, OfferView offer) { }
}
