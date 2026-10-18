package vn.ttcs.recruitment.offerdelivery.http;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OfferHttpConfiguration {
    @Bean
    public FilterRegistrationBean<OfferRequestFilter> offerFilterRegistration(
            OfferRequestFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // Installed once inside Spring Security, after CORS and before bearer authentication.
        registration.setEnabled(false);
        return registration;
    }
}
