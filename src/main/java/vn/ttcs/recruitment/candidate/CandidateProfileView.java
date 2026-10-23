package vn.ttcs.recruitment.candidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Comparator;
import vn.ttcs.recruitment.screening.ScreeningView;

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
 * date an application outside the scope. S5-04 tags/notes aggregate readable applications only and retain applicationId.
 * Per-application screening holds the latest shared rating and a bounded notes preview. toString shows ids only.
 * Task 345: updatedAt also counts the withdrawnAt of the returned applications (a withdrawal is the only change of an
 * application after its receipt in Sprint 4), still never anything of an application outside the scope.
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
        List<ScreeningView.Tag> tags,
        List<ScreeningView.Note> notes,
        List<ApplicationDetail> applications) {

    public CandidateProfileView {
        tags = List.copyOf(tags);
        notes = List.copyOf(notes);
        applications = List.copyOf(applications);
    }

    // Built from the readable applications, newest first (the repository's order: received_at, then id), and from
    // nothing else. The caller never passes an empty list: a reader with nothing readable is refused before a view
    // exists.
    static CandidateProfileView of(UUID candidateId, List<ReadableApplication> readable,
                                   Map<UUID, ScreeningView> screening) {
        if (readable.isEmpty()) {
            throw new IllegalArgumentException("A profile shows at least one readable application");
        }
        List<ApplicationDetail> applications = new ArrayList<>(readable.size());
        for (int index = 0; index < readable.size(); index++) {
            applications.add(readable.get(index).detail(index == 0, screening.get(readable.get(index).id())));
        }
        ApplicationDetail newest = applications.getFirst();
        // Never promote one application's contact data over another's: one difference blanks both.
        boolean mismatch = applications.stream().anyMatch(application ->
                !application.fullName().equals(newest.fullName()) || !application.phone().equals(newest.phone()));
        Instant createdAt = applications.stream().map(ApplicationDetail::appliedAt).min(Instant::compareTo)
                .orElseThrow();
        // Task 345: a withdrawal is the newest change of an application in Sprint 4.
        Instant updatedAt = applications.stream().map(CandidateProfileView::lastChange).max(Instant::compareTo)
                .orElseThrow();
        var tags = applications.stream().filter(a -> a.screening() != null)
                .flatMap(a -> a.screening().tags().stream()).toList();
        // A small preview only. Each application's screening endpoint pages its entire internal note history.
        var notes = applications.stream().filter(a -> a.screening() != null)
                .flatMap(a -> a.screening().notes().stream())
                .sorted(Comparator.comparing(ScreeningView.Note::createdAt).thenComparing(ScreeningView.Note::id).reversed())
                .limit(20).toList();
        return new CandidateProfileView(candidateId, mismatch ? null : newest.fullName(), newest.email(),
                mismatch ? null : newest.phone(), mismatch, newest.id(), createdAt, updatedAt, tags, notes,
                applications);
    }

    private static Instant lastChange(ApplicationDetail application) {
        Instant withdrawnAt = application.withdrawnAt();
        return withdrawnAt != null && withdrawnAt.isAfter(application.appliedAt()) ? withdrawnAt : application.appliedAt();
    }

    @Override
    public String toString() {
        return "CandidateProfileView[" + id + ", " + applications.size() + " applications, personal data redacted]";
    }
}
