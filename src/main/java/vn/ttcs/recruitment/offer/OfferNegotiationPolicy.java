package vn.ttcs.recruitment.offer;

import java.util.Set;

/**
 * Pure status guard for offer negotiation.
 *
 * <p>An offer may be revised (replaced by a new offer) only while its status is
 * {@code APPROVED}, {@code SENT} or {@code DECLINED}. A replacement offer always starts a new
 * draft and must go through a fresh approval; the old approval is never reused. An
 * {@code ACCEPTED} offer can never be replaced.
 *
 * <p>This class only checks the status. It does not authorize actors or reopen applications.
 * The service must separately check the current session, scope, version, the latest candidate
 * decline event, the active requisition/position and fresh offer terms.
 */
public final class OfferNegotiationPolicy {

    private static final Set<String> REVISABLE_STATUSES = Set.of("APPROVED", "SENT", "DECLINED");

    private OfferNegotiationPolicy() {
    }

    /**
     * Returns {@code true} only for the exact statuses {@code APPROVED}, {@code SENT} and
     * {@code DECLINED}; {@code false} for {@code null} and any other value.
     */
    public static boolean canRevise(String status) {
        if (status == null) {
            return false;
        }
        return REVISABLE_STATUSES.contains(status);
    }
}
