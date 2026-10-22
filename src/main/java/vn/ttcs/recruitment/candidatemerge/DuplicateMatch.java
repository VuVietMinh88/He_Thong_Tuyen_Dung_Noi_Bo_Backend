package vn.ttcs.recruitment.candidatemerge;

/**
 * Task 367 (story S5-01): what two applications share, so a recruiter sees why a candidate is suggested as a duplicate.
 * EMAIL: the effective normalized email; PHONE: the effective phone in its national form (CandidateDuplicateRepository).
 */
public enum DuplicateMatch {
    EMAIL,
    PHONE
}
