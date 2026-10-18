package vn.ttcs.recruitment.screening;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal assessment of exactly one application. Staff emails never appear in this contract. */
public record ScreeningView(UUID applicationId, List<Tag> tags, Rating rating, List<Note> notes,
                            long totalNotes, int notesPage, int notesSize) {
    public ScreeningView {
        tags = List.copyOf(tags);
        notes = List.copyOf(notes);
    }

    public record Tag(UUID applicationId, UUID id, String code, String name, boolean active) { }
    public record Rating(int value, UUID updatedBy, String updatedByName, Instant updatedAt) { }
    public record Note(UUID id, UUID applicationId, String content, UUID authorId, String authorName,
                       Instant createdAt) {
        @Override public String toString() { return "Note[" + id + ", content redacted]"; }
    }

    public boolean hasAssessment() { return !tags.isEmpty() || rating != null || totalNotes > 0; }
    @Override public String toString() { return "ScreeningView[" + applicationId + ", internal data redacted]"; }
}
