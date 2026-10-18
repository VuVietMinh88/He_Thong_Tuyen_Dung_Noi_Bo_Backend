package vn.ttcs.recruitment.screening;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Called only after record authorization, inside the caller's transaction. No candidate/email-wide queries. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ScreeningRepository {
    private final JdbcTemplate jdbc;

    public ScreeningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ScreeningView view(UUID applicationId, int page, int size) {
        var tags = jdbc.query("""
                SELECT c.id, c.code, c.name, c.active
                FROM application_screening_tags t JOIN recruitment_catalog_items c ON c.id = t.tag_id
                WHERE t.application_id = ? ORDER BY c.sort_order, c.name, c.id
                """, (rs, n) -> new ScreeningView.Tag(applicationId, rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getBoolean("active")), applicationId);
        var ratings = jdbc.query("""
                SELECT r.rating, r.updated_by, u.full_name, r.updated_at
                FROM application_screening_ratings r JOIN user_accounts u ON u.id = r.updated_by
                WHERE r.application_id = ?
                """, (rs, n) -> new ScreeningView.Rating(rs.getInt("rating"), rs.getObject("updated_by", UUID.class),
                rs.getString("full_name"), rs.getTimestamp("updated_at").toInstant()), applicationId);
        Long total = jdbc.queryForObject("SELECT count(*) FROM application_screening_notes WHERE application_id = ?",
                Long.class, applicationId);
        var notes = jdbc.query("""
                SELECT n.id, n.content, n.author_id, u.full_name, n.created_at
                FROM application_screening_notes n JOIN user_accounts u ON u.id = n.author_id
                WHERE n.application_id = ? ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> new ScreeningView.Note(rs.getObject("id", UUID.class), applicationId,
                rs.getString("content"), rs.getObject("author_id", UUID.class), rs.getString("full_name"),
                rs.getTimestamp("created_at").toInstant()), applicationId, size, (long) page * size);
        return new ScreeningView(applicationId, tags, ratings.isEmpty() ? null : ratings.getFirst(), notes,
                total == null ? 0 : total, page, size);
    }

    UUID requisitionOf(UUID applicationId) {
        return jdbc.query("SELECT requisition_id FROM job_applications WHERE id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), applicationId).stream().findFirst().orElse(null);
    }

    boolean lockRequisition(UUID id) {
        return !jdbc.queryForList("SELECT id FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE", UUID.class,
                id).isEmpty();
    }

    boolean lockApplication(UUID id) {
        return !jdbc.queryForList("SELECT id FROM job_applications WHERE id = ? FOR NO KEY UPDATE", UUID.class,
                id).isEmpty();
    }

    boolean lockActiveTag(UUID id) {
        return jdbc.query("SELECT catalog_type, active FROM recruitment_catalog_items WHERE id = ? FOR SHARE",
                (rs, n) -> "CANDIDATE_TAG".equals(rs.getString("catalog_type")) && rs.getBoolean("active"), id)
                .stream().findFirst().orElse(false);
    }

    void replaceTags(UUID applicationId, List<UUID> tags, UUID actor, Instant now) {
        jdbc.update("DELETE FROM application_screening_tags WHERE application_id = ?", applicationId);
        for (UUID tag : tags) {
            jdbc.update("INSERT INTO application_screening_tags(application_id,tag_id,added_by,added_at) VALUES (?,?,?,?)",
                    applicationId, tag, actor, Timestamp.from(now));
        }
    }

    void rate(UUID applicationId, int rating, UUID actor, Instant now) {
        jdbc.update("""
                INSERT INTO application_screening_ratings(application_id,rating,updated_by,updated_at) VALUES (?,?,?,?)
                ON CONFLICT(application_id) DO UPDATE SET rating = EXCLUDED.rating,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """, applicationId, rating, actor, Timestamp.from(now));
    }

    void addNote(UUID noteId, UUID applicationId, String content, UUID actor, Instant now) {
        jdbc.update("INSERT INTO application_screening_notes(id,application_id,content,author_id,created_at) VALUES (?,?,?,?,?)",
                noteId, applicationId, content, actor, Timestamp.from(now));
    }
}
