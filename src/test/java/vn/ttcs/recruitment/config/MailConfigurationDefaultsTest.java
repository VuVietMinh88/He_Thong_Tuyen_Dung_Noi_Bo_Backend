package vn.ttcs.recruitment.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MailConfigurationDefaultsTest {

    @Test
    void developmentDefaultsUseTheLocalMailCatcherAndTheProjectSender() throws IOException {
        StandardEnvironment environment = environment(Map.of());

        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("spring.mail.port")).isEqualTo("1025");
        assertThat(environment.getProperty("app.password-reset.mail-from")).isEqualTo("support@internal-hire.com");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable")).isEqualTo("false");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.ssl.enable")).isEqualTo("false");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(environment.getProperty("app.password-reset.page-url")).isEqualTo("http://localhost:5173/reset-password");
        assertThat(environment.getProperty("app.account-activation.page-url"))
                .isEqualTo("http://localhost:5173/activate-account");
    }

    @Test
    void productionValuesFromEnvironmentVariablesReachTheMailAndLinkSettings() throws IOException {
        StandardEnvironment environment = environment(Map.of(
                "MAIL_HOST", "emailserver4-186.serverpoint.com", "MAIL_PORT", "465",
                "MAIL_SMTP_AUTH", "true", "MAIL_SMTP_STARTTLS", "false", "MAIL_SMTP_SSL", "true",
                "MAIL_USERNAME", "support@internal-hire.com",
                "RESET_PASSWORD_PAGE_URL", "https://internal-hire.com/reset-password",
                "ACCOUNT_ACTIVATION_PAGE_URL", "https://internal-hire.com/activate-account",
                "CORS_ALLOWED_ORIGINS", "https://internal-hire.com,https://www.internal-hire.com"));

        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("emailserver4-186.serverpoint.com");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(environment.getProperty("spring.mail.port")).isEqualTo("465");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.auth")).isEqualTo("true");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable")).isEqualTo("false");
        assertThat(environment.getProperty("spring.mail.username")).isEqualTo("support@internal-hire.com");
        assertThat(environment.getProperty("app.password-reset.page-url")).isEqualTo("https://internal-hire.com/reset-password");
        assertThat(environment.getProperty("app.account-activation.page-url"))
                .isEqualTo("https://internal-hire.com/activate-account");
        assertThat(environment.getProperty("app.cors.allowed-origins"))
                .isEqualTo("https://internal-hire.com,https://www.internal-hire.com");
    }

    // Variables take precedence over application.properties, the same order Spring Boot uses at runtime.
    private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        environment.getPropertySources().addLast(
                new ResourcePropertySource(new ClassPathResource("application.properties")));
        return environment;
    }
}
