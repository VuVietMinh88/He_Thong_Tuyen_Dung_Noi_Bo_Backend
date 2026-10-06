package vn.ttcs.recruitment.companyprofile;

import java.util.UUID;

// A logo or introduction image as the public page shows it: its id and pixel size (width, height),
// so the page can reserve the right space before the picture loads.
public record CompanyMediaView(UUID id, int width, int height) {

    static CompanyMediaView from(CompanyMediaSummary media) {
        return new CompanyMediaView(media.id(), media.width(), media.height());
    }
}
