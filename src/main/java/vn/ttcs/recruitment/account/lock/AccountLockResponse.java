package vn.ttcs.recruitment.account.lock;

import vn.ttcs.recruitment.account.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountLockResponse(UUID userId, AccountStatus status, String lockReason,
                                  Instant lockedAt, UUID lockedBy, String handoverWarning) {
}
