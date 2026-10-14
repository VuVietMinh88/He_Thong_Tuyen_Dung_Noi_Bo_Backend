package vn.ttcs.recruitment.application;

import java.time.Instant;

record ApplicationConfirmationPayload(String email, String fullName, String postingTitle, Instant receivedAt,
        String trackingCode, String trackingUrl, String from) {
    @Override public String toString() { return "ApplicationConfirmationPayload[personal data redacted]"; }
}
