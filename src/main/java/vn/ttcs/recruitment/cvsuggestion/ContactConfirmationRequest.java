package vn.ttcs.recruitment.cvsuggestion;

import java.util.UUID;
import jakarta.validation.constraints.*;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.companyprofile.PlainText;

public record ContactConfirmationRequest(
        @NotBlank @Size(max=120) @PlainText(multiline=false) String fullName,
        @NotBlank @Email @Size(max=254) @PlainText(multiline=false) String email,
        @NotBlank @Size(max=32) @Pattern(regexp="\\+?[0-9]{8,15}") String phone,
        @NotNull @Min(0) Long expectedRevision,
        @NotNull @Pattern(regexp="[a-f0-9]{64}") String expectedCvHash,
        @NotNull ContactSource nameSource,@NotNull ContactSource emailSource,@NotNull ContactSource phoneSource,
        UUID extractionId) {
    public ContactConfirmationRequest {
        var normalized=new ApplicationSubmission(fullName,email,phone,null,null);
        fullName=normalized.fullName();email=normalized.email();phone=normalized.phone();
    }
    ContactValues values() { return new ContactValues(fullName,email,phone); }
    boolean usesExtraction() { return nameSource==ContactSource.EXTRACTED || emailSource==ContactSource.EXTRACTED || phoneSource==ContactSource.EXTRACTED; }
    @Override public String toString() { return "ContactConfirmationRequest[personal data redacted]"; }
}
