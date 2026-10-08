package vn.ttcs.recruitment.account;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "app.bootstrap", name = "enabled", havingValue = "true")
public class BootstrapAdmin implements ApplicationRunner {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Validator validator;
    private final String email;
    private final String password;

    public BootstrapAdmin(AccountRepository accounts, PasswordEncoder passwordEncoder, Clock clock,
                          Validator validator, @Value("${app.bootstrap.email}") String email,
                          @Value("${app.bootstrap.password}") String password) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.validator = validator;
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Restarting must never recreate an admin or replace an existing account's credentials.
        if (accounts.count() != 0) {
            return;
        }
        if (!validator.validate(new BootstrapEmail(email)).isEmpty()) {
            throw new IllegalStateException("Set a valid BOOTSTRAP_ADMIN_EMAIL for initial setup.");
        }
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !password.matches("(?s).*\\p{L}.*") || !password.matches("(?s).*[0-9].*")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD needs at least 8 characters, a letter and a digit, and at most 72 UTF-8 bytes.");
        }
        accounts.save(new Account(email, "Quản trị viên", passwordEncoder.encode(password),
                Set.of(Role.ADMIN), clock.instant()));
    }

    private record BootstrapEmail(@NotBlank @Email @Size(max = 254) String email) {
    }
}
