package vn.ttcs.recruitment.application;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationTrackingConfiguration {
    @Bean
    public FilterRegistrationBean<ApplicationTrackingRequestFilter> trackingFilterRegistration(
            ApplicationTrackingRequestFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // Task 339: runs once inside Spring Security, after CORS and before bearer authentication and body parsing.
        registration.setEnabled(false);
        return registration;
    }
}
