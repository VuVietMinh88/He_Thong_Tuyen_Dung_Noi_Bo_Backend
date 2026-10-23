package vn.ttcs.recruitment.candidate;

import java.util.UUID;

/**
 * Task 363 (story S4-09): one existing application as CandidateAccess.findApplication sees it for a reader: its id, the
 * candidate profile it is linked to (V32) and whether the reader may read it (CandidateAccess.READABLE). Ids only, so
 * toString shows no personal data.
 */
public record ApplicationAccess(UUID applicationId, UUID candidateId, boolean readable) { }
