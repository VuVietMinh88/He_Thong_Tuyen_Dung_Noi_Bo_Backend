package vn.ttcs.recruitment.jobposting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

// Task 303: everyone who wrote the content of a posting (V21 job_posting_authors): its creator and every account that
// saved it. Rows are only added, never removed. The review of task 308 refuses APPROVE to all of them, because HR and
// ADMIN may edit every posting and also review it.
@Repository
class JobPostingAuthorRepository {
    private final NamedParameterJdbcTemplate jdbc;

    JobPostingAuthorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // The caller holds the posting row lock (create: the posting was just inserted). Saving again changes nothing.
    void add(UUID postingId, UUID accountId) {
        jdbc.update("""
                INSERT INTO job_posting_authors (job_posting_id, account_id) VALUES (:posting, :account)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource("posting", postingId).addValue("account", accountId));
    }

    Set<UUID> findAuthors(UUID postingId) {
        return Set.copyOf(jdbc.query("SELECT account_id FROM job_posting_authors WHERE job_posting_id = :posting",
                new MapSqlParameterSource("posting", postingId), (row, number) -> row.getObject("account_id",
                        UUID.class)));
    }
}
