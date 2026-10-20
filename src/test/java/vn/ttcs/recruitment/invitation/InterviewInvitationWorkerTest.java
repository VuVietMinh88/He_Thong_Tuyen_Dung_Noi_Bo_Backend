package vn.ttcs.recruitment.invitation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailPreparationException;

import vn.ttcs.recruitment.messaging.EmailTemplate;
import vn.ttcs.recruitment.messaging.EmailTemplateType;
import vn.ttcs.recruitment.messaging.EmailTemplates;
import vn.ttcs.recruitment.messaging.TemplateVariable;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InterviewInvitationWorkerTest {
    private final InterviewInvitationSettings settings = mock(InterviewInvitationSettings.class);
    private final InterviewInvitationOutboxRepository outbox = mock(InterviewInvitationOutboxRepository.class);
    private final InterviewInvitationCrypto crypto = mock(InterviewInvitationCrypto.class);
    private final InterviewInvitationMailSender mail = mock(InterviewInvitationMailSender.class);
    private final EmailTemplates templates = mock(EmailTemplates.class);
    private final InterviewInvitationWorker worker = new InterviewInvitationWorker(settings, outbox, crypto, mail, templates);
    // Task 533: the candidate's first invitation is rendered from the INTERVIEW_INVITATION template.
    private final EmailTemplate.Rendered template = new EmailTemplate.Rendered(EmailTemplateType.INTERVIEW_INVITATION, 2,
            "Mời phỏng vấn Developer", List.of("Chào Candidate,", "Lúc 09:00 Thứ Ba, ngày 01/01/2030."));
    private final InterviewInvitationOutboxRepository.Delivery delivery =
            new InterviewInvitationOutboxRepository.Delivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    0, UUID.randomUUID(), null, "INVITATION", "v1", new byte[12], new byte[20], UUID.randomUUID(), 1);
    private final InterviewMailPayload payload = new InterviewMailPayload("candidate@example.test", "Candidate",
            "Developer", "Technical", Instant.parse("2030-01-01T02:00:00Z"), Instant.parse("2030-01-01T03:00:00Z"),
            "ONSITE", "Room A", null, "Prepare CV", "INVITATION", "https://example.test/reply#token=private", null);
    private final String messageId = "<interview-invitation." + delivery.id() + "@example.test>";

    @BeforeEach void ready() {
        when(settings.enabled()).thenReturn(true);
        when(settings.deliveryEnabled()).thenReturn(true);
        when(settings.ready()).thenReturn(true);
        when(settings.messageId(delivery.id())).thenReturn(messageId);
        when(outbox.claim()).thenReturn(Optional.of(delivery));
        when(crypto.decrypt(delivery.id(), delivery.interviewId(), delivery.keyId(), delivery.nonce(), delivery.ciphertext()))
                .thenReturn(payload);
        when(outbox.renew(delivery)).thenReturn(true);
        when(settings.zone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
        when(templates.render(EmailTemplateType.INTERVIEW_INVITATION, Map.of(TemplateVariable.CANDIDATE_NAME, "Candidate",
                TemplateVariable.POSITION_TITLE, "Developer", TemplateVariable.INTERVIEW_TIME,
                "09:00 Thứ Ba, ngày 01/01/2030"))).thenReturn(template);
    }

    @Test void disabledDeliveryNeverClaimsOrContactsSmtp() {
        when(settings.deliveryEnabled()).thenReturn(false);
        assertThat(worker.processNext()).isFalse();
        verifyNoInteractions(outbox, crypto, mail);
    }

    @Test void disabledFeatureNeverClaimsOrContactsSmtp() {
        when(settings.enabled()).thenReturn(false);
        assertThat(worker.processNext()).isFalse();
        verifyNoInteractions(outbox, crypto, mail);
    }

    @Test void missingKeyLeavesPendingQueueUntouched() {
        when(settings.ready()).thenReturn(false);
        assertThat(worker.processNext()).isFalse();
        verifyNoInteractions(outbox, crypto, mail);
    }

    @Test void invalidCiphertextIsTerminalWithoutDelivering() {
        when(crypto.decrypt(delivery.id(), delivery.interviewId(), delivery.keyId(), delivery.nonce(), delivery.ciphertext()))
                .thenThrow(new IllegalStateException("Invalid encrypted invitation"));
        assertThat(worker.processNext()).isTrue();
        verify(outbox).failed(delivery, true, "PAYLOAD_INVALID");
        verify(outbox, never()).renew(any());
        verifyNoInteractions(mail);
    }

    @Test void rescheduledInvitationIsDiscardedBeforeSmtp() {
        when(outbox.unavailableReason(delivery, payload)).thenReturn("OBSOLETE");
        assertThat(worker.processNext()).isTrue();
        verify(outbox).abandon(delivery, "OBSOLETE");
        verify(outbox, never()).renew(any());
        verifyNoInteractions(mail);
    }

    @Test void removedOrDisabledRecipientIsNotMailed() {
        when(outbox.unavailableReason(delivery, payload)).thenReturn("RECIPIENT_UNAVAILABLE");
        assertThat(worker.processNext()).isTrue();
        verify(outbox).abandon(delivery, "RECIPIENT_UNAVAILABLE");
        verifyNoInteractions(mail);
    }

    @Test void supersededLeaseCannotDeliver() {
        when(outbox.renew(delivery)).thenReturn(false);
        assertThat(worker.processNext()).isTrue();
        verifyNoInteractions(mail);
        verify(outbox, never()).sent(any());
    }

    @Test void temporarySmtpFailureSchedulesRetryWithoutRecordingSuccess() {
        doThrow(new MailSendException("Synthetic SMTP failure")).when(mail).send(payload, messageId, template);
        assertThat(worker.processNext()).isTrue();
        verify(outbox).failed(delivery, false, "RECIPIENT_REFUSED");
        verify(outbox, never()).sent(any());
    }

    @Test void invalidMailContentIsTerminalWithoutAnSmtpRetry() {
        doThrow(new MailPreparationException("Invalid invitation content")).when(mail).send(payload, messageId, template);
        assertThat(worker.processNext()).isTrue();
        verify(outbox).failed(delivery, true, "PAYLOAD_INVALID");
        verify(outbox, never()).sent(any());
    }

    @Test void successfulDeliveryUsesStableMessageIdAndFencedResult() {
        assertThat(worker.processNext()).isTrue();
        var sequence = inOrder(outbox, mail);
        sequence.verify(outbox).claim();
        sequence.verify(outbox).unavailableReason(delivery, payload);
        sequence.verify(outbox).renew(delivery);
        sequence.verify(mail).send(payload, messageId, template);
        sequence.verify(outbox).sent(delivery);
        assertThat(messageId).contains(delivery.id().toString());
        assertThat(delivery.toString()).doesNotContain(delivery.id().toString(), "INVITATION");
    }

    @Test void staffAndUpdatedEmailsKeepTheFixedTextWithoutReadingTheTemplate() {
        var staff = new InterviewInvitationOutboxRepository.Delivery(delivery.id(), delivery.interviewId(),
                delivery.applicationId(), 0, delivery.invitationId(), UUID.randomUUID(), "INVITATION", "v1", new byte[12],
                new byte[20], delivery.leaseToken(), 1);
        var updated = new InterviewInvitationOutboxRepository.Delivery(delivery.id(), delivery.interviewId(),
                delivery.applicationId(), 0, delivery.invitationId(), null, "UPDATED", "v1", new byte[12],
                new byte[20], delivery.leaseToken(), 1);
        for (var row : List.of(staff, updated)) {
            reset(mail);
            when(outbox.claim()).thenReturn(Optional.of(row));
            when(crypto.decrypt(row.id(), row.interviewId(), row.keyId(), row.nonce(), row.ciphertext())).thenReturn(payload);
            when(outbox.renew(row)).thenReturn(true);
            assertThat(worker.processNext()).isTrue();
            verify(mail).send(payload, messageId, null);
        }
        verifyNoInteractions(templates);
    }

    @Test void messageIdUsesConfiguredSenderDomainAndStaysStableForEachOutboxRow() {
        var actual = new InterviewInvitationSettings(false, false,
                java.net.URI.create("https://portal.example.test/reply"), "Hiring <support@company.example>",
                "v1", "", "Asia/Ho_Chi_Minh");
        assertThat(actual.messageId(delivery.id())).isEqualTo("<interview-invitation." + delivery.id() + "@company.example>")
                .isEqualTo(actual.messageId(delivery.id()));
        assertThat(actual.messageId(UUID.randomUUID())).isNotEqualTo(actual.messageId(delivery.id()));
    }
}
