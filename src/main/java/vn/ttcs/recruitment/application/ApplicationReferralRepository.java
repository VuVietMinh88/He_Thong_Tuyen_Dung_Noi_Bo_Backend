package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Task 350 (story S4-07): writes the referral row of V36. MANDATORY: it belongs to the application's own transaction
 * (ReferralApplicationStorage.store), after the application row its key needs, so a duplicate (409), a closed posting
 * (404), a failed CV insert or the caller's rollback leaves no referral behind, and a referral never exists without
 * its application.
 */
@Repository
class ApplicationReferralRepository {
    private final NamedParameterJdbcTemplate jdbc;

    ApplicationReferralRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // The key to user_accounts takes FOR KEY SHARE on the referrer's account (the referral API holds it already).
    @Transactional(propagation = Propagation.MANDATORY)
    void insert(UUID applicationId, UUID referrerId, String note, Instant consentDeclaredAt) {
        jdbc.update("""
                INSERT INTO application_referrals (application_id, referrer_id, note, consent_declared_at)
                VALUES (:application, :referrer, :note, :consent)
                """, new MapSqlParameterSource("application", applicationId).addValue("referrer", referrerId)
                .addValue("note", note).addValue("consent", Timestamp.from(consentDeclaredAt)));
    }
}
