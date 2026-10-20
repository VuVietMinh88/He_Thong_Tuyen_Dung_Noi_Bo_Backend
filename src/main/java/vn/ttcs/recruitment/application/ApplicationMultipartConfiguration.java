package vn.ttcs.recruitment.application;

import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import jakarta.servlet.http.HttpServletRequest;

@Configuration(proxyBeanMethods = false)
public class ApplicationMultipartConfiguration {
    @Bean
    public FilterRegistrationBean<ApplicationIntakeFilter> intakeFilterRegistration(ApplicationIntakeFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // Runs once inside Spring Security, after CORS and before bearer authentication/body parsing.
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public StandardServletMultipartResolver multipartResolver(MultipartProperties properties) {
        var resolver = new StandardServletMultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                // This route reads a bounded streaming multipart body. All existing uploads retain the Servlet
                // parser and its original 5 MiB/file, 6 MiB/request configuration and temporary-file behavior.
                return !ApplicationIntakeRoutes.isSubmission(request) && super.isMultipart(request);
            }
        };
        resolver.setResolveLazily(properties.isResolveLazily());
        resolver.setStrictServletCompliance(properties.isStrictServletCompliance());
        return resolver;
    }
}
