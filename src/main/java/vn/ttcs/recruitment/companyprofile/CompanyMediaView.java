package vn.ttcs.recruitment.companyprofile;

import java.util.UUID;

// A logo or introduction image as the public page shows it: its id, its pixel size (width, height), so the
// page can reserve the right space before the picture loads, and the url that returns the picture.
public record CompanyMediaView(UUID id, int width, int height, String url) {

    static CompanyMediaView from(CompanyMediaSummary media) {
        return new CompanyMediaView(media.id(), media.width(), media.height(), publicUrl(media.id()));
    }

    // Path of GET /api/v1/public/company-media/{id} (CompanyProfileController), without the server address,
    // so the portal adds the same API address it uses for every other call.
    static String publicUrl(UUID id) {
        return "/api/v1/public/company-media/" + id;
    }
}
