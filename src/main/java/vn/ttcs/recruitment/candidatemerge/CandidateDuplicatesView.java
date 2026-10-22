package vn.ttcs.recruitment.candidatemerge;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 367 (story S5-01): the other candidates that may be the same person as candidateId, the most recent match
 * first. Each one says how it matched (over all its matching applications) and lists its matching applications the
 * reader may read, newest first. truncated: more candidates matched than are listed; moreApplications: the
 * candidate has more matching applications than are listed. Nothing here is the candidate's own data.
 */
public record CandidateDuplicatesView(UUID candidateId, List<Duplicate> duplicates, boolean truncated) {

    public record Duplicate(UUID candidateId, List<DuplicateMatch> matchedBy, List<Application> applications,
                            boolean moreApplications) { }

    public record Application(UUID applicationId, UUID requisitionId, String postingTitle, String fullName,
                              ApplicationStage stage, String stageName, Instant receivedAt,
                              List<DuplicateMatch> matchedBy) {
        @Override public String toString() { return "Application[" + applicationId + ", personal data redacted]"; }
    }

    // The repository's rows (ordered by candidate rank, then application rank) cut to the limits.
    static CandidateDuplicatesView of(UUID candidateId, List<CandidateDuplicateRepository.Row> rows) {
        Map<UUID, List<CandidateDuplicateRepository.Row>> grouped = new LinkedHashMap<>();
        boolean truncated = false;
        for (CandidateDuplicateRepository.Row row : rows) {
            if (row.candidateRank() > CandidateDuplicateRepository.MAX_CANDIDATES) {
                truncated = true;
                continue;
            }
            grouped.computeIfAbsent(row.candidateId(), key -> new ArrayList<>()).add(row);
        }
        List<Duplicate> duplicates = new ArrayList<>();
        grouped.forEach((candidate, matches) -> {
            List<Application> applications = matches.stream()
                    .filter(row -> row.applicationRank() <= CandidateDuplicateRepository.MAX_APPLICATIONS)
                    .map(row -> new Application(row.applicationId(), row.requisitionId(), row.postingTitle(),
                            row.fullName(), row.stage(), row.stage().displayName(), row.receivedAt(),
                            matched(row.byEmail(), row.byPhone())))
                    .toList();
            CandidateDuplicateRepository.Row first = matches.getFirst();
            duplicates.add(new Duplicate(candidate, matched(first.candidateByEmail(), first.candidateByPhone()),
                    applications, matches.size() > applications.size()));
        });
        return new CandidateDuplicatesView(candidateId, List.copyOf(duplicates), truncated);
    }

    private static List<DuplicateMatch> matched(boolean email, boolean phone) {
        List<DuplicateMatch> matched = new ArrayList<>(2);
        if (email) matched.add(DuplicateMatch.EMAIL);
        if (phone) matched.add(DuplicateMatch.PHONE);
        return List.copyOf(matched);
    }
}
