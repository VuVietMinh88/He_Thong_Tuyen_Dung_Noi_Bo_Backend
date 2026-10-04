package vn.ttcs.recruitment.auth.passwordreset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    boolean existsByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    // The condition is checked in PostgreSQL, so two requests cannot consume one link.
    @Modifying
    @Query("update PasswordResetToken token set token.usedAt = :now "
            + "where token.tokenHash = :hash and token.usedAt is null and token.expiresAt > :now")
    int consume(@Param("hash") String hash, @Param("now") Instant now);

    @Modifying
    @Query("update PasswordResetToken token set token.usedAt = :now "
            + "where token.userId = :userId and token.usedAt is null")
    int invalidateForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
