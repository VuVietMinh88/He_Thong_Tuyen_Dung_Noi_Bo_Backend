package vn.ttcs.recruitment.account;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// The 409 a form shows under the phone field, and which failed writes become it: only the unique violation of V38's
// phone constraint, wherever it sits in the cause chain. Any other failure is passed on unchanged.
class PhoneAlreadyUsedExceptionTest {

    @Test
    void theAnswerIsA409WithAFieldErrorOnPhoneThatNamesNoAccount() {
        var exception = new PhoneAlreadyUsedException();

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getCode()).isEqualTo("PHONE_ALREADY_EXISTS");
        assertThat(exception.getMessage()).isEqualTo("Số điện thoại đã được sử dụng cho một tài khoản khác.");
        assertThat(exception.getFieldErrors()).isEqualTo(Map.of("phone",
                "Số điện thoại này đã được dùng cho một tài khoản khác. Vui lòng nhập số khác."));
    }

    @Test
    void theUniqueViolationOfThePhoneConstraintIsFoundAnywhereInTheCauseChain() {
        var violation = violation("23505", "user_accounts_phone_key");
        var translated = new DataIntegrityViolationException("could not execute statement", violation);
        var wrapped = new DataIntegrityViolationException("flush failed", new IllegalStateException(violation));

        assertThat(PhoneAlreadyUsedException.isPhoneConstraint(violation)).isTrue();
        assertThat(PhoneAlreadyUsedException.isPhoneConstraint(translated)).isTrue();
        assertThat(PhoneAlreadyUsedException.isPhoneConstraint(wrapped)).isTrue();
        assertThat(PhoneAlreadyUsedException.translate(translated)).isInstanceOf(PhoneAlreadyUsedException.class);
        assertThat(PhoneAlreadyUsedException.translate(wrapped)).isInstanceOf(PhoneAlreadyUsedException.class);
    }

    @Test
    void everyOtherFailureIsPassedOnUnchanged() {
        var failures = new DataIntegrityViolationException[]{
                // The email is another 409 with its own code.
                new DataIntegrityViolationException("email", violation("23505", "user_accounts_email_key")),
                // Same name but not a unique violation, or a unique violation without a known name.
                new DataIntegrityViolationException("check", violation("23514", "user_accounts_phone_key")),
                new DataIntegrityViolationException("unnamed", violation("23505", null)),
                // A plain SQLException without Hibernate's constraint name.
                new DataIntegrityViolationException("jdbc", new SQLException(
                        "duplicate key value violates unique constraint \"user_accounts_phone_key\"", "23505")),
                new DataIntegrityViolationException("no cause")
        };
        for (var failure : failures) {
            assertThat(PhoneAlreadyUsedException.isPhoneConstraint(failure)).as(failure.getMessage()).isFalse();
            assertThat(PhoneAlreadyUsedException.translate(failure)).as(failure.getMessage()).isSameAs(failure);
        }
        assertThat(PhoneAlreadyUsedException.isPhoneConstraint(null)).isFalse();
    }

    private static ConstraintViolationException violation(String sqlState, String constraintName) {
        return new ConstraintViolationException("could not execute statement",
                new SQLException("ERROR: constraint violated", sqlState), constraintName);
    }
}
