package vn.ttcs.recruitment.candidateduplicate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.cvsuggestion.ContactValues;
import static vn.ttcs.recruitment.candidateduplicate.DuplicateCandidateView.*;

@Repository
public class CandidateDuplicateRepository {
    // All detector instances share one bounded batch writer. Intake never takes this lock.
    static final long DETECTION_LOCK = 367_10300L;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public CandidateDuplicateRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc; this.named = named;
    }

    record Application(UUID id, UUID requisition, UUID department) { }
    record Source(UUID id, long revision, String email, String phone) {
        @Override public String toString() { return "DuplicateSource[personal data redacted]"; }
    }

    Application application(UUID id) {
        var rows = jdbc.query("""
                SELECT a.requisition_id,r.department_id FROM job_applications a
                JOIN recruitment_requisitions r ON r.id=a.requisition_id WHERE a.id=?
                """, (rs,n) -> new Application(id, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ ứng tuyển.");
        return rows.getFirst();
    }

    List<Source> pending(int limit) {
        return jdbc.query("""
                SELECT v.application_id,v.contact_revision,v.email,v.phone FROM candidate_effective_contacts v
                LEFT JOIN candidate_contact_index i ON i.application_id=v.application_id
                LEFT JOIN candidate_memberships m ON m.application_id=v.application_id
                WHERE i.application_id IS NULL OR m.application_id IS NULL OR i.contact_revision<>v.contact_revision
                   OR i.candidate_id<>m.candidate_id OR i.normalizer_version<>?
                   OR i.normalized_email<>v.normalized_email OR i.phone<>v.phone
                ORDER BY v.application_id LIMIT ?
                """, (rs,n) -> new Source(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4)),
                ContactMatchKeys.VERSION, limit);
    }

    void index(Source source) {
        // Deterministic singleton identity, without changing an existing (future merged) membership.
        jdbc.update("""
                INSERT INTO candidate_profiles(id,representative_application_id) SELECT ?,? WHERE NOT EXISTS
                (SELECT 1 FROM candidate_memberships WHERE application_id=?) ON CONFLICT DO NOTHING
                """, source.id(), source.id(), source.id());
        jdbc.update("INSERT INTO candidate_memberships(application_id,candidate_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                source.id(), source.id());
        var keys = ContactMatchKeys.from(source.email(), source.phone());
        jdbc.update("""
                INSERT INTO candidate_contact_index(application_id,candidate_id,contact_revision,normalizer_version,normalized_email,phone,indexed_at)
                VALUES (?,(SELECT candidate_id FROM candidate_memberships WHERE application_id=?),?,?,?,?,clock_timestamp())
                ON CONFLICT(application_id) DO UPDATE SET candidate_id=EXCLUDED.candidate_id,
                contact_revision=EXCLUDED.contact_revision,normalizer_version=EXCLUDED.normalizer_version,
                normalized_email=EXCLUDED.normalized_email,phone=EXCLUDED.phone,indexed_at=EXCLUDED.indexed_at
                """, source.id(), source.id(), source.revision(), ContactMatchKeys.VERSION, keys.email(), keys.phone());
        jdbc.update("DELETE FROM candidate_duplicate_pairs WHERE left_application_id=? OR right_application_id=?", source.id(), source.id());
        jdbc.update("""
                INSERT INTO candidate_duplicate_pairs(left_application_id,right_application_id,left_revision,right_revision,
                    email_match,phone_match,detected_at)
                SELECT LEAST(s.application_id,o.application_id),GREATEST(s.application_id,o.application_id),
                    CASE WHEN s.application_id<o.application_id THEN s.contact_revision ELSE o.contact_revision END,
                    CASE WHEN s.application_id<o.application_id THEN o.contact_revision ELSE s.contact_revision END,
                    s.normalized_email=o.normalized_email,s.phone=o.phone,clock_timestamp()
                FROM candidate_contact_index s JOIN candidate_contact_index o ON
                    (s.normalized_email=o.normalized_email OR s.phone=o.phone) AND s.application_id<>o.application_id
                JOIN candidate_effective_contacts v ON v.application_id=o.application_id
                    AND v.contact_revision=o.contact_revision AND v.normalized_email=o.normalized_email AND v.phone=o.phone
                JOIN candidate_memberships sm ON sm.application_id=s.application_id AND sm.candidate_id=s.candidate_id
                JOIN candidate_memberships om ON om.application_id=o.application_id AND om.candidate_id=o.candidate_id
                    AND om.candidate_id<>sm.candidate_id
                WHERE s.application_id=? AND o.normalizer_version=?
                ON CONFLICT(left_application_id,right_application_id) DO UPDATE SET
                    left_revision=EXCLUDED.left_revision,right_revision=EXCLUDED.right_revision,
                    email_match=EXCLUDED.email_match,phone_match=EXCLUDED.phone_match,detected_at=EXCLUDED.detected_at
                """, source.id(), ContactMatchKeys.VERSION);
    }

    // Validate cached edges against both current contacts and memberships on EVERY read.
    // An edit hides stale warnings immediately, before the asynchronous detector catches up.
    private static final String MATCHES = """
            WITH matches AS (
                SELECT CASE WHEN p.left_application_id=:application THEN p.right_application_id ELSE p.left_application_id END AS other_id,
                    p.email_match,p.phone_match,p.detected_at
                FROM candidate_duplicate_pairs p
                JOIN candidate_contact_index l ON l.application_id=p.left_application_id
                JOIN candidate_contact_index ri ON ri.application_id=p.right_application_id
                JOIN candidate_effective_contacts lc ON lc.application_id=l.application_id
                JOIN candidate_effective_contacts rc ON rc.application_id=ri.application_id
                JOIN candidate_memberships lm ON lm.application_id=l.application_id AND lm.candidate_id=l.candidate_id
                JOIN candidate_memberships rm ON rm.application_id=ri.application_id AND rm.candidate_id=ri.candidate_id
                    AND rm.candidate_id<>lm.candidate_id
                WHERE (p.left_application_id=:application OR p.right_application_id=:application)
                    AND l.normalizer_version=:version AND ri.normalizer_version=:version
                    AND p.left_revision=lc.contact_revision AND p.right_revision=rc.contact_revision
                    AND l.contact_revision=lc.contact_revision AND ri.contact_revision=rc.contact_revision
                    AND l.normalized_email=lc.normalized_email AND l.phone=lc.phone
                    AND ri.normalized_email=rc.normalized_email AND ri.phone=rc.phone
                    AND p.email_match=(l.normalized_email=ri.normalized_email) AND p.phone_match=(l.phone=ri.phone)
            )
            """;

    DuplicateCandidateView view(Application app, CandidateDuplicateAccess.Caller caller, int page, int size) {
        var parameters = caller.parameters().addValue("application", app.id()).addValue("version", ContactMatchKeys.VERSION)
                .addValue("limit", size + 1).addValue("offset", (long) page * size);
        var state = jdbc.queryForMap("""
                SELECT v.contact_revision,CASE WHEN i.contact_revision=v.contact_revision AND i.normalizer_version=?
                    AND i.normalized_email=v.normalized_email AND i.phone=v.phone AND i.candidate_id=m.candidate_id
                    THEN i.indexed_at END AS indexed_at
                FROM candidate_effective_contacts v LEFT JOIN candidate_contact_index i ON i.application_id=v.application_id
                LEFT JOIN candidate_memberships m ON m.application_id=v.application_id WHERE v.application_id=?
                """, ContactMatchKeys.VERSION, app.id());
        var items = named.query(MATCHES + """
                SELECT v.application_id,v.requisition_id,v.posting_title,v.full_name,v.email,v.phone,v.contact_revision,
                    matches.email_match,matches.phone_match,matches.detected_at
                FROM matches JOIN candidate_effective_contacts v ON v.application_id=matches.other_id
                JOIN recruitment_requisitions r ON r.id=v.requisition_id WHERE
                """ + caller.visibleSql() + " ORDER BY matches.detected_at DESC,v.application_id LIMIT :limit OFFSET :offset",
                parameters, (rs,n) -> {
                    var reasons = new ArrayList<Match>();
                    if (rs.getBoolean("email_match")) reasons.add(Match.EMAIL);
                    if (rs.getBoolean("phone_match")) reasons.add(Match.PHONE);
                    return new Item(rs.getObject("application_id", UUID.class), rs.getObject("requisition_id", UUID.class),
                            rs.getString("posting_title"), new ContactValues(rs.getString("full_name"),rs.getString("email"),rs.getString("phone")),
                            rs.getLong("contact_revision"), reasons, rs.getTimestamp("detected_at").toInstant());
                });
        boolean restricted = caller.scope() != vn.ttcs.recruitment.security.AccessScope.ALL
                && Boolean.TRUE.equals(named.queryForObject(MATCHES + """
                    SELECT EXISTS(SELECT 1 FROM matches JOIN candidate_effective_contacts v ON v.application_id=matches.other_id
                    JOIN recruitment_requisitions r ON r.id=v.requisition_id WHERE NOT
                    """ + caller.visibleSql() + ")", parameters, Boolean.class));
        Instant indexed = state.get("indexed_at") == null ? null : ((Timestamp) state.get("indexed_at")).toInstant();
        return new DuplicateCandidateView(app.id(), ((Number) state.get("contact_revision")).longValue(),
                indexed == null ? DetectionStatus.PENDING : DetectionStatus.AVAILABLE, indexed,
                items.subList(0, Math.min(size, items.size())), page, size, items.size() > size,
                restricted ? "Có hồ sơ có thể trùng ngoài phạm vi phụ trách" : null);
    }
}
