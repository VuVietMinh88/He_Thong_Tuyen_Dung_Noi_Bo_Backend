package vn.ttcs.recruitment.account;

public final class ProfileValidation {
    public static final String VIETNAM_PHONE_PATTERN = "(?:0[35789][0-9]{8}|02[0-9]{9})";

    private ProfileValidation() { }

    public static String optionalText(String value) {
        if (value == null) { return null; }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String normalizePhone(String value) {
        String phone = optionalText(value);
        if (phone != null && phone.startsWith("+84")) {
            return "0" + phone.substring(3);
        }
        return phone;
    }
}
