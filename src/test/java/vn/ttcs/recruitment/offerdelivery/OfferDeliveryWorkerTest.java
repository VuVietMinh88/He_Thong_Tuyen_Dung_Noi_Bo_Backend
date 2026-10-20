package vn.ttcs.recruitment.offerdelivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.messaging.EmailTemplate;
import vn.ttcs.recruitment.messaging.EmailTemplateType;
import vn.ttcs.recruitment.messaging.EmailTemplates;
import vn.ttcs.recruitment.messaging.TemplateVariable;
import vn.ttcs.recruitment.offer.OfferMailPayload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OfferDeliveryWorkerTest {
    private final OfferDeliverySettings settings=mock(OfferDeliverySettings.class);
    private final OfferOutboxRepository outbox=mock(OfferOutboxRepository.class);
    private final OfferDeliveryCrypto crypto=mock(OfferDeliveryCrypto.class);
    private final OfferDeliveryMailSender mail=mock(OfferDeliveryMailSender.class);
    private final EmailTemplates templates=mock(EmailTemplates.class);
    private final OfferDeliveryWorker worker=new OfferDeliveryWorker(settings,outbox,crypto,mail,templates);
    // Task 533: the OFFER template rendered for this payload's candidate and position.
    private final EmailTemplate.Rendered template=new EmailTemplate.Rendered(EmailTemplateType.OFFER,3,"Thư offer Java",
            List.of("Chào Candidate,","Mời bạn nhận vị trí Java."));
    private final OfferOutboxRepository.Delivery delivery=new OfferOutboxRepository.Delivery(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            1,UUID.randomUUID(),"v1",new byte[12],new byte[20],UUID.randomUUID(),1);
    private final OfferMailPayload payload=new OfferMailPayload("a@example.test","Candidate","Java",20000000,0,
            LocalDate.of(2030,2,1),Instant.parse("2030-01-01T00:00:00Z"),"https://portal.test/reply#token="+"a".repeat(43));
    @BeforeEach void ready() {
        when(settings.enabled()).thenReturn(true); when(settings.deliveryEnabled()).thenReturn(true); when(settings.ready()).thenReturn(true);
        when(outbox.claim()).thenReturn(Optional.of(delivery)); when(outbox.renew(delivery)).thenReturn(true);
        when(crypto.decrypt(delivery.id(),delivery.offerId(),delivery.keyId(),delivery.nonce(),delivery.ciphertext())).thenReturn(payload);
        when(templates.render(EmailTemplateType.OFFER,Map.of(TemplateVariable.CANDIDATE_NAME,"Candidate",
                TemplateVariable.POSITION_TITLE,"Java"))).thenReturn(template);
    }
    @Test void deliveryOffDoesNotClaimOrDecryptOrConnect() {
        when(settings.deliveryEnabled()).thenReturn(false); assertThat(worker.processNext()).isFalse(); verifyNoInteractions(outbox,crypto,mail);
    }
    @Test void lostOrExpiredLeaseNeverSends() {
        when(outbox.renew(delivery)).thenReturn(false); assertThat(worker.processNext()).isTrue(); verifyNoInteractions(mail);
    }
    @Test void templateReadFailureLeavesTheLeaseToExpireWithoutSmtp() {
        when(templates.render(any(),any())).thenThrow(new IllegalStateException("Email template OFFER is missing"));
        org.assertj.core.api.Assertions.assertThatThrownBy(worker::processNext).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mail); verify(outbox,never()).sent(any()); verify(outbox,never()).failed(any(),anyBoolean(),any());
    }
    @Test void cancellationBetweenPreflightAndRenewalIsCaughtBeforeSmtp() {
        when(outbox.unavailableReason(delivery,payload)).thenReturn(null,"OBSOLETE");
        assertThat(worker.processNext()).isTrue(); verifyNoInteractions(mail); verify(outbox).abandon(delivery,"OBSOLETE");
    }
    @Test void recipientChangeDuringRenewalIsCaughtBeforeSmtp() {
        when(outbox.unavailableReason(delivery,payload)).thenReturn(null,"RECIPIENT_UNAVAILABLE");
        assertThat(worker.processNext()).isTrue(); verifyNoInteractions(mail); verify(outbox).abandon(delivery,"RECIPIENT_UNAVAILABLE");
    }
    @Test void successfulWorkerChecksStateAgainAfterLeaseAndFinishesWithSameLease() {
        when(settings.messageId(delivery.id())).thenReturn("<stable@example.test>");
        assertThat(worker.processNext()).isTrue(); var order=inOrder(outbox,mail);
        order.verify(outbox).claim(); order.verify(outbox).unavailableReason(delivery,payload);
        order.verify(outbox).renew(delivery); order.verify(outbox).unavailableReason(delivery,payload);
        order.verify(mail).send(payload,"<stable@example.test>",template); order.verify(outbox).sent(delivery);
    }
}
