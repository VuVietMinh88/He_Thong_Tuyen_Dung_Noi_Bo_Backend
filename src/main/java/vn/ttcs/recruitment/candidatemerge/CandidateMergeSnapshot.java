package vn.ttcs.recruitment.candidatemerge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** IDs and revisions only: never duplicates the submitted CV or contact values in the merge journal. */
public record CandidateMergeSnapshot(List<Group> groups, List<Member> members) {
    public record Group(UUID id, long revision, UUID representativeApplicationId, UUID mergeHeadId) { }
    public record Member(UUID applicationId, UUID candidateId, long contactRevision) { }

    public CandidateMergeSnapshot {
        groups = groups.stream().sorted(Comparator.comparing(g -> g.id().toString())).toList();
        members = members.stream().sorted(Comparator.comparing(m -> m.applicationId().toString())).toList();
    }

    public String fingerprint() {
        var text = new StringBuilder("candidate-merge-snapshot-v1\n");
        for (var group : groups) text.append(group.id()).append('|').append(group.revision()).append('|')
                .append(group.representativeApplicationId()).append('|').append(group.mergeHeadId()).append('\n');
        text.append("members\n");
        for (var member : members) text.append(member.applicationId()).append('|').append(member.candidateId())
                .append('|').append(member.contactRevision()).append('\n');
        return HexFormat.of().formatHex(hash(text.toString()));
    }

    static byte[] hash(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    @Override public String toString() { return "CandidateMergeSnapshot[internal data redacted]"; }
}
