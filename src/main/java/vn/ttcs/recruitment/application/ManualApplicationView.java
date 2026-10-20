package vn.ttcs.recruitment.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 407 (story S5-08): the application a staff member entered by hand, as stored. Never the tracking code (it goes
 * to the candidate by email), the CV or the email of the staff member. Task 408: duplicates lists the readable
 * applications the entry was grouped with (same email) and those of other emails with the same phone.
 */
public record ManualApplicationView(UUID applicationId, UUID candidateId, UUID requisitionId, String postingTitle,
                                    String fullName, ApplicationStage stage, String stageName, Instant receivedAt,
                                    Staff enteredBy, ManualApplicationDuplicates.Report duplicates) {
    public record Staff(UUID id, String fullName) { }

    @Override
    public String toString() { return "ManualApplicationView[" + applicationId + ", personal data redacted]"; }
}
