package vn.ttcs.recruitment.offer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OfferNegotiationPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "SENT", "DECLINED"})
    void allowsRevisableStatuses(String status) {
        assertThat(OfferNegotiationPolicy.canRevise(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DRAFT",
            "CHANGES_REQUESTED",
            "PENDING_HR",
            "PENDING_HIGHER",
            "ACCEPTED",
            "REJECTED",
            "EXPIRED",
            "CANCELLED",
            "SUPERSEDED"
    })
    void blocksOtherExistingStatuses(String status) {
        assertThat(OfferNegotiationPolicy.canRevise(status)).isFalse();
    }

    @Test
    void blocksNull() {
        assertThat(OfferNegotiationPolicy.canRevise(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "UNKNOWN",
            "approved",
            "Approved",
            "sent",
            "Sent",
            "declined",
            "DeClInEd",
            " APPROVED",
            "SENT ",
            "DECLINED\n"
    })
    void blocksUnknownAndMixedCaseStatuses(String status) {
        assertThat(OfferNegotiationPolicy.canRevise(status)).isFalse();
    }
}
