package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
class ApplicationCvRepository {
    private final NamedParameterJdbcTemplate jdbc;

    ApplicationCvRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    void insert(StoredApplication application, ValidatedCv cv) {
        jdbc.update("""
                INSERT INTO application_cvs (application_id, filename, content_type, size_bytes, sha256, content, received_at)
                VALUES (:id, :filename, :type, :size, :hash, :content, :at)
                """, new MapSqlParameterSource("id", application.id()).addValue("filename", cv.filename())
                .addValue("type", cv.contentType()).addValue("size", cv.sizeBytes()).addValue("hash", cv.sha256())
                .addValue("content", cv.content()).addValue("at", Timestamp.from(application.receivedAt())));
    }
}
