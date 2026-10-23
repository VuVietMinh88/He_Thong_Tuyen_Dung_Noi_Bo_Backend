package vn.ttcs.recruitment.application;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.support.TestPropertySourceUtils;
import vn.ttcs.recruitment.account.AccountInvitationMailSender;
import vn.ttcs.recruitment.auth.passwordreset.PasswordResetMailSender;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.mail.MailLayout;
import vn.ttcs.recruitment.mail.MailParts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationCommonMailConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"legacy","common","environment"})
    void allThreeSendersShareOneFromWhileLegacyOverridesRemainCompatible(String mode) throws Exception {
        var messages=new ArrayList<MimeMessage>();
        var sender=new JavaMailSenderImpl(){@Override public void send(MimeMessage message){messages.add(message);}};
        sender.getJavaMailProperties().setProperty("mail.host","localhost");
        sender.getJavaMailProperties().setProperty("mail.user","test-fixture");
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
            if(mode.equals("environment"))TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"MAIL_FROM=environment@example.test");
            else TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"app.password-reset.mail-from=legacy@example.test");
            if(mode.equals("common"))TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"app.mail.from=common@example.test");
            context.registerBean(JavaMailSender.class,() -> sender);
            context.registerBean(BusinessCalendar.class,
                    () -> new BusinessCalendar(Clock.systemUTC(), ZoneId.of("Asia/Ho_Chi_Minh")));
            // Task 345: the confirmation sender builds the withdrawal link from the settings.
            context.register(AccountInvitationMailSender.class,PasswordResetMailSender.class,ApplicationConfirmationMailSender.class,
                    ApplicationConfirmationSettings.class, MailLayout.class);
            context.refresh();
            context.getBean(AccountInvitationMailSender.class).send("recipient@example.test","TestOnlyPassword","test-token",Duration.ofHours(24));
            context.getBean(PasswordResetMailSender.class).send("recipient@example.test","test-token");
            context.getBean(ApplicationConfirmationMailSender.class).send(new ApplicationConfirmationPayload("recipient@example.test","Candidate","Developer",Instant.now(),"A".repeat(43),"https://portal.example.test/tra-cuu#ma="+"A".repeat(43),
                    context.getEnvironment().getRequiredProperty("app.mail.from")),"<test@example.test>");
            assertThat(messages).hasSize(3);
            String expected=mode+"@example.test";
            for(var message:messages)assertThat(message.getFrom()[0].toString()).isEqualTo(expected);
            // The common sender alias must retain the feedback branch's HTML/text account-email layout.
            for (var message : messages.subList(0, 2)) {
                message.saveChanges();
                assertThat(MailParts.text(message)).contains("test-token");
                assertThat(MailParts.html(message)).contains("test-token");
                assertThat(message.getReplyTo()).extracting(Object::toString)
                        .containsExactly(context.getEnvironment().getRequiredProperty("app.mail.support-email"));
            }
            assertThat(List.of(messages.get(0).getSubject(),messages.get(1).getSubject(),messages.get(2).getSubject()))
                    .containsExactly("Kích hoạt tài khoản — Hệ thống tuyển dụng nội bộ","Đặt lại mật khẩu — Hệ thống tuyển dụng nội bộ",
                            "Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ");
        }
    }
}
