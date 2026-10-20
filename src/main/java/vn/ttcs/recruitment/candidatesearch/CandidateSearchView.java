package vn.ttcs.recruitment.candidatesearch;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 392 (story S5-05): one page of the candidate search, newest submission first. An item is one application, as
 * the board shows it plus its source, tags and rating. matchedIn names the fields of that application that held the
 * keyword (NAME, EMAIL, PHONE) and, task 393, COVER_LETTER when coverLetterKeyword was given. No screening note, CV,
 * cover letter text or tracking data is part of an item.
 */
public record CandidateSearchView(List<Item> items, long total, int page, int size, boolean hasNext) {
    public record Item(UUID applicationId, UUID candidateId, UUID requisitionId, UUID positionId, String positionCode,
                       String positionName, String postingTitle, String fullName, String email, String phone,
                       ApplicationStage stage, String stageName, String sourceName, List<String> tags, Integer rating,
                       Instant receivedAt, List<String> matchedIn) {
        @Override public String toString() { return "Item[" + applicationId + ", personal data redacted]"; }
    }
}
