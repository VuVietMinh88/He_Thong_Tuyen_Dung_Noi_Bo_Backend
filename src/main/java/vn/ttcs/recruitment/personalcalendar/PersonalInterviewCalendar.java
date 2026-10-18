package vn.ttcs.recruitment.personalcalendar;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Only appointment preparation and the caller's own submission state; never another person's scores. */
public record PersonalInterviewCalendar(List<Item> items, long totalElements, int page, int size,
                                        Instant from, Instant to, boolean includePending) {
    public record Item(UUID id, UUID applicationId, String candidateName, UUID positionId, String positionName,
                       String roundName, Instant startsAt, Instant endsAt, String mode, String room,
                       String meetingUrl, EvaluationState evaluationState, boolean evaluationSubmitted, boolean needsSubmission,
                       boolean cvAvailable, String cvUrl) {
        @Override
        public String toString() { return "PersonalInterviewCalendar.Item[id=" + id + ", personal data redacted]"; }
    }

    public enum EvaluationState { NOT_STARTED, DRAFT, SUBMITTED, REOPENED }
}
