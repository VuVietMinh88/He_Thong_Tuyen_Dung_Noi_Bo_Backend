package vn.ttcs.recruitment.companyprofile;

import java.util.UUID;

// Answer of POST /company-profile/media: the stored picture without its bytes. The editor sends id back as
// logoMediaId or in imageIds. url is the public address, which answers only once the saved page uses the picture.
public record CompanyMediaUploadView(UUID id, CompanyMediaKind kind, String contentType, int sizeBytes, int width,
                                     int height, String url) {

    static CompanyMediaUploadView from(CompanyMedia media) {
        return new CompanyMediaUploadView(media.getId(), media.getKind(), media.getContentType(),
                media.getSizeBytes(), media.getWidth(), media.getHeight(), CompanyMediaView.publicUrl(media.getId()));
    }
}
