package vn.ttcs.recruitment.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTypeTest {
    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void onlyApprovalPendingIsMandatory(NotificationType type) {
        boolean mandatory = switch (type) {
            case APPROVAL_PENDING -> true;
            case RECRUITER_ASSIGNED, INTERVIEW_INVITED, OFFER_RESPONDED, SLA_OVERDUE -> false;
        };
        assertThat(type.mandatory()).isEqualTo(mandatory);
        assertThat(type.displayName()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(NotificationLink.Type.class)
    void interviewAndOfferLinksNameTheirApplicationAndOthersDoNot(NotificationLink.Type type) {
        boolean child = switch (type) {
            case INTERVIEW, OFFER -> true;
            case REQUISITION, JOB_POSTING, APPLICATION -> false;
        };
        UUID id = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        if (child) {
            assertThat(new NotificationLink(type, id, parent).parentId()).isEqualTo(parent);
            assertThatThrownBy(() -> new NotificationLink(type, id, null)).isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThat(new NotificationLink(type, id, null).parentId()).isNull();
            assertThatThrownBy(() -> new NotificationLink(type, id, parent)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void factoriesBuildTheMatchingLinks() {
        UUID id = UUID.randomUUID(), application = UUID.randomUUID();
        assertThat(NotificationLink.offer(id, application)).isEqualTo(new NotificationLink(NotificationLink.Type.OFFER, id, application));
        assertThat(NotificationLink.requisition(id).type()).isEqualTo(NotificationLink.Type.REQUISITION);
        assertThat(NotificationLink.jobPosting(id).type()).isEqualTo(NotificationLink.Type.JOB_POSTING);
        assertThat(NotificationLink.application(id).type()).isEqualTo(NotificationLink.Type.APPLICATION);
        assertThat(NotificationLink.interview(id, application).parentId()).isEqualTo(application);
    }
}
