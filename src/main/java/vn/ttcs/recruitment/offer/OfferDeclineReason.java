package vn.ttcs.recruitment.offer;

/**
 * Categories a candidate can pick when declining an offer.
 */
public enum OfferDeclineReason {
    SALARY("Lương"),
    LOCATION("Địa điểm"),
    OTHER_JOB("Nhận việc nơi khác"),
    PERSONAL("Lý do cá nhân");

    private static final String INVALID_REASON_MESSAGE = "Unsupported offer decline reason";

    private final String label;

    OfferDeclineReason(String label) {
        this.label = label;
    }

    /**
     * Returns the Vietnamese label shown to users.
     */
    public String label() {
        return label;
    }

    /**
     * Parses an optional decline reason.
     *
     * <p>{@code null} means "no reason given" and returns {@code null}. Any other value must match
     * an enum name exactly (case-sensitive, no trimming). Blank or unknown values are rejected.
     * The exception message never contains the raw input.
     *
     * @param raw the submitted value, may be {@code null}
     * @return the matching reason, or {@code null} when {@code raw} is {@code null}
     * @throws IllegalArgumentException when {@code raw} is not an exact enum name
     */
    public static OfferDeclineReason parseOptional(String raw) {
        if (raw == null) {
            return null;
        }
        for (OfferDeclineReason reason : values()) {
            if (reason.name().equals(raw)) {
                return reason;
            }
        }
        throw new IllegalArgumentException(INVALID_REASON_MESSAGE);
    }
}
