package vn.ttcs.recruitment.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("update AuthSession session set session.revokedAt = :now "
            + "where session.userId = :userId and session.revokedAt is null")
    int revokeForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSession session set session.revokedAt = :now "
            + "where session.userId = :userId and session.id <> :currentSessionId "
            + "and session.revokedAt is null")
    int revokeOtherSessions(@Param("userId") UUID userId,
                            @Param("currentSessionId") UUID currentSessionId,
                            @Param("now") Instant now);
}
