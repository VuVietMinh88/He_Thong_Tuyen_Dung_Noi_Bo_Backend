package vn.ttcs.recruitment.candidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task 361 (story S4-09): the body of GET /api/v1/candidates/{id}/unified-profile, with the frontend's UnifiedCandidate
 * names. It is built from the applications the reader may read and from nothing else, so an application outside the
 * reader's scope adds no key, id, title, date, stage or count (BA/PO question 25(7)).
 * <p>
 * Contact data stays per application (ApplicationDetail): linking uses an unverified email (task 360), so whoever knows
 * a candidate's email can add an application with another name and phone. The header therefore shows fullName and
 * phone only when every returned application has the same name and the same phone; otherwise both are null and
 * contactMismatch is true. email is the email the newest returned application typed (they all share one normalized
 * form, which is never shown). currentApplicationId is that newest application; createdAt and updatedAt are the
 * earliest and the newest appliedAt of the returned applications, never the profile's own creation time, which may
 * date an application outside the scope. tags and notes are always empty until S5-04. toString shows ids only.
 */
public record CandidateProfileView(
        UUID id,
        String fullName,
        String email,
        String phone,
        boolean contactMismatch,
        UUID currentApplicationId,
        Instant createdAt,
        Instant updatedAt,
        List<Object> tags,
        List<Object> notes,
        List<ApplicationDetail> applications) {

    public CandidateProfileView {
        tags = List.copyOf(tags);
        notes = List.copyOf(notes);
        applications = List.copyOf(applications);
    }

    // Built from the readable applications, newest first (the repository's order: received_at, then id), and from
    // nothing else. The caller never passes an empty list: a reader with nothing readable is refused before a view
    // exists.
    static CandidateProfileView of(UUID candidateId, List<ReadableApplication> readable) {
        if (readable.isEmpty()) {
            throw new IllegalArgumentException("A profile shows at least one readable application");
        }
        List<ApplicationDetail> applications = new ArrayList<>(readable.size());
        for (int index = 0; index < readable.size(); index++) {
            applications.add(readable.get(index).detail(index == 0));
        }
        ApplicationDetail newest = applications.getFirst();
        // Never promote one application's contact data over another's: one difference blanks both.
        boolean mismatch = applications.stream().anyMatch(application ->
                !application.fullName().equals(newest.fullName()) || !application.phone().equals(newest.phone()));
        Instant createdAt = applications.stream().map(ApplicationDetail::appliedAt).min(Instant::compareTo)
                .orElseThrow();
        Instant updatedAt = applications.stream().map(ApplicationDetail::appliedAt).max(Instant::compareTo)
                .orElseThrow();
        return new CandidateProfileView(candidateId, mismatch ? null : newest.fullName(), newest.email(),
                mismatch ? null : newest.phone(), mismatch, newest.id(), createdAt, updatedAt, List.of(), List.of(),
                applications);
    }

    @Override
    public String toString() {
        return "CandidateProfileView[" + id + ", " + applications.size() + " applications, personal data redacted]";
    }
}
