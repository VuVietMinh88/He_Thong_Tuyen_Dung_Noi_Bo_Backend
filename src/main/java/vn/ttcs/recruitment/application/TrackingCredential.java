package vn.ttcs.recruitment.application;

// Task 339: what a candidate proves by knowing the code of one application and the email it was sent from: the
// SHA-256 of the code's bytes (never the code) and the normalized email. Compared only in SQL, through the UNIQUE index
// on the hash, like reset tokens.
record TrackingCredential(byte[] hash, String normalizedEmail) {
    @Override public String toString() { return "TrackingCredential[hash and email redacted]"; }
}
