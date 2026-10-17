package vn.ttcs.recruitment.candidateduplicate;

import vn.ttcs.recruitment.application.ApplicationSubmission;

/** Exact business comparison; deliberately does not turn a leading 0 into +84. */
record ContactMatchKeys(String email, String phone) {
    static final String VERSION = "contact-exact-v1";

    static ContactMatchKeys from(String email, String phone) {
        var normalized = new ApplicationSubmission(null, email, phone, null, null);
        return new ContactMatchKeys(normalized.normalizedEmail(), normalized.phone());
    }

    @Override public String toString() { return "ContactMatchKeys[personal data redacted]"; }
}
