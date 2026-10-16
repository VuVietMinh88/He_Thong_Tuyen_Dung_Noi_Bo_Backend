package vn.ttcs.recruitment.companyprofile;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// The text must be plain text, never HTML: PlainTextValidator explains the exact rule.
// A null or empty value is accepted here; use @NotBlank when the field is required.
@Documented
@Constraint(validatedBy = PlainTextValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlainText {

    String message() default "Chỉ được nhập văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML hoặc ký tự điều khiển.";

    // true allows line breaks and tabs (long introduction); false keeps the text on one line.
    boolean multiline() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
