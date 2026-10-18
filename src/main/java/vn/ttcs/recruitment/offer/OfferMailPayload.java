package vn.ttcs.recruitment.offer;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What the offer email shows to one candidate. The salary and the allowance are whole monthly amounts in VND. A null
 * reply link means a preview: the same text without the link to accept or decline.
 *
 * <p>It only carries what the candidate may read; the internal salary band, the justification and the approval notes
 * of an offer never reach this record. toString hides every value, so logging a payload leaks neither personal data,
 * money nor the reply token.
 */
public record OfferMailPayload(
        String email,
        String recipientName,
        String positionTitle,
        long salary,
        long allowance,
        LocalDate startDate,
        Instant responseDeadline,
        String replyUrl) {

    private static final String REDACTED = "[redacted]";

    @Override
    public String toString() {
        return "OfferMailPayload[email=" + REDACTED
                + ", recipientName=" + REDACTED
                + ", positionTitle=" + REDACTED
                + ", salary=" + REDACTED
                + ", allowance=" + REDACTED
                + ", startDate=" + REDACTED
                + ", responseDeadline=" + REDACTED
                + ", replyUrl=" + REDACTED + "]";
    }
}
