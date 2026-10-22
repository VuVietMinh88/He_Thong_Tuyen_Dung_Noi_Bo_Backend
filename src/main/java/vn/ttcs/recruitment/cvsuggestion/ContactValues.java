package vn.ttcs.recruitment.cvsuggestion;

public record ContactValues(String fullName,String email,String phone) {
    @Override public String toString() { return "ContactValues[personal data redacted]"; }
}
