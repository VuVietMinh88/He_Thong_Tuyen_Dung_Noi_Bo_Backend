package vn.ttcs.recruitment.application;

import java.util.UUID;

/**
 * Task 413 (story S5-09): published by ApplicationStorageService.store inside its transaction, after the application
 * and its candidate link are written and before the last caller and deadline checks (and the CV row), so listeners
 * (the fit result) join that transaction, roll back with it, and any wait inside them is followed by those checks.
 * Ids only.
 */
public record ApplicationStoredEvent(UUID applicationId, UUID requisitionId) { }
