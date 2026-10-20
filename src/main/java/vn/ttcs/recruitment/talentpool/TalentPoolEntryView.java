package vn.ttcs.recruitment.talentpool;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 398 (story S5-06): one talent pool entry. The candidate's name and contact details are the source application's
 * own snapshot, shown only to readers of that application. No CV, cover letter, screening note or tracking data.
 * candidateStatus says whether the candidate is free to contact, already in another active pipeline, or hired.
 */
public record TalentPoolEntryView(UUID entryId, UUID candidateId, UUID sourceApplicationId, UUID sourceRequisitionId,
                                  String sourcePostingTitle, String fullName, String email, String phone,
                                  TalentPoolEvidence evidence, String evidenceName, String reason,
                                  List<String> titleInterests, List<String> skills, Instant addedAt, Staff addedBy,
                                  Instant removedAt, Staff removedBy, TalentPoolCandidateStatus candidateStatus,
                                  String candidateStatusName) {
    public record Staff(UUID id, String fullName) { }

    public TalentPoolEntryView {
        titleInterests = List.copyOf(titleInterests);
        skills = List.copyOf(skills);
    }

    @Override public String toString() { return "TalentPoolEntryView[" + entryId + ", personal data redacted]"; }
}
