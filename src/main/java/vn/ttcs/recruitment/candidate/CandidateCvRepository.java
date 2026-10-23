package vn.ttcs.recruitment.candidate;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Task 362 (story S4-09): the reads behind GET /api/v1/applications/{id}/cv. Both run in the caller's read transaction
 * (MANDATORY, ApplicationCvService: REPEATABLE READ, so they see the same row) and take no lock. The metadata and the
 * bytes are separate statements so that the bytes are read only after the access check and the metadata check.
 */
@Repository
class CandidateCvRepository {
    private final NamedParameterJdbcTemplate jdbc;

    CandidateCvRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // The stored name, type and size; empty when the application has no CV row.
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Metadata> metadata(UUID applicationId) {
        return jdbc.query("SELECT filename, content_type, size_bytes FROM application_cvs WHERE application_id = :id",
                new MapSqlParameterSource("id", applicationId),
                (row, number) -> new Metadata(row.getString("filename"), row.getString("content_type"),
                        row.getInt("size_bytes"))).stream().findFirst();
    }

    // The bytes (at most 10 MiB, CvValidator.MAX_BYTES). Call it only once the reader may read the application.
    @Transactional(propagation = Propagation.MANDATORY)
    byte[] content(UUID applicationId) {
        return jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id = :id",
                new MapSqlParameterSource("id", applicationId), byte[].class);
    }

    // The file name is personal data: toString leaves it out.
    record Metadata(String filename, String contentType, int sizeBytes) {
        @Override
        public String toString() {
            return "Metadata[filename redacted, contentType=" + contentType + ", size=" + sizeBytes + "]";
        }
    }
}
