package vn.ttcs.recruitment.interview;

import java.util.List;
import java.util.UUID;

/** Minimal scheduling directory: no account email, phone, roles or security state. */
public record InterviewParticipantPage(List<Item> items, long totalElements, int page, int size) {
    public record Item(UUID id, String fullName) { }
}
