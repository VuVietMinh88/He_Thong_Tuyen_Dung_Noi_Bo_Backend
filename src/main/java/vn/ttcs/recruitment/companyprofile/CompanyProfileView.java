package vn.ttcs.recruitment.companyprofile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// The saved page for HR's editor. The content fields have the same names as CompanyProfileRequest,
// so the editor can send them back with PUT after changing what it needs.
public record CompanyProfileView(String companyName, String tagline, String introduction, UUID logoMediaId,
                                 List<UUID> imageIds, Instant createdAt, Instant updatedAt, UUID updatedBy) {

    static CompanyProfileView from(CompanyProfile profile) {
        return new CompanyProfileView(profile.getCompanyName(), profile.getTagline(), profile.getIntroduction(),
                profile.getLogoMediaId(), profile.getImageIds(), profile.getCreatedAt(), profile.getUpdatedAt(),
                profile.getUpdatedBy());
    }
}
