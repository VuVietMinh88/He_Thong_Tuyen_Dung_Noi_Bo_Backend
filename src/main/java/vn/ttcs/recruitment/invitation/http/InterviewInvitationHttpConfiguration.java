package vn.ttcs.recruitment.invitation.http;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InterviewInvitationHttpConfiguration {
    @Bean
    public FilterRegistrationBean<InterviewInvitationRequestFilter> invitationFilterRegistration(
            InterviewInvitationRequestFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // Installed once inside Spring Security, after CORS and before bearer authentication.
        registration.setEnabled(false);
        return registration;
    }
}
