package vn.ttcs.recruitment.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 407 (story S5-08): the application a staff member entered by hand, as stored. Never the tracking code (it goes
 * to the candidate by email), the CV or the email of the staff member.
 */
public record ManualApplicationView(UUID applicationId, UUID candidateId, UUID requisitionId, String postingTitle,
                                    String fullName, ApplicationStage stage, String stageName, Instant receivedAt,
                                    Staff enteredBy) {
    public record Staff(UUID id, String fullName) { }

    @Override
    public String toString() { return "ManualApplicationView[" + applicationId + ", personal data redacted]"; }
}
