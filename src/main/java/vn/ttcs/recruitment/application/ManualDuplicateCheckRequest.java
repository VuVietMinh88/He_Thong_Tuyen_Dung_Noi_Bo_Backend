package vn.ttcs.recruitment.application;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Task 408 (story S5-08): the contact details a staff member is about to enter, checked against the existing
 * applications before the entry. Validated by ApplicationSubmission's own rules (ManualApplicationDuplicateService).
 */
public record ManualDuplicateCheckRequest(String email, String phone) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported duplicate check field");
    }

    @Override public String toString() { return "ManualDuplicateCheckRequest[personal data redacted]"; }
}
