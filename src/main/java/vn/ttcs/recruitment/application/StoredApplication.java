package vn.ttcs.recruitment.application;

import java.time.Instant;
import java.util.UUID;

public record StoredApplication(UUID id, UUID jobPostingId, UUID requisitionId, ApplicationStage stage,
                                Instant receivedAt) { }
