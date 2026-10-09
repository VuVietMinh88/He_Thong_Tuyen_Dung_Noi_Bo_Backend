package vn.ttcs.recruitment.companyprofile;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.UUID;

// Company introduction page of the recruitment portal (story S2-09). The /api/v1/company-profile endpoints are
// HR's editor and need JOB_POSTINGS_WRITE_ALL; the /api/v1/public endpoints are for candidates, need no login
// and return only what candidates may see.
@RestController
public class CompanyProfileController {
    // A picture never changes after upload, so browsers and proxies may keep it for an hour. After that hour
    // they ask again, and a picture that was removed from the page then answers 404.
    private static final CacheControl PUBLIC_PICTURE_CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final CompanyProfileService service;

    public CompanyProfileController(CompanyProfileService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/company-profile")
    public ResponseEntity<CompanyProfileView> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt));
    }

    // Creates the page on the first call and replaces all of its content afterwards.
    @PutMapping("/api/v1/company-profile")
    public ResponseEntity<CompanyProfileView> save(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CompanyProfileRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(jwt, request));
    }

    @PostMapping("/api/v1/company-profile/preview")
    public ResponseEntity<PublicCompanyProfileView> preview(@AuthenticationPrincipal Jwt jwt,
                                                            @Valid @RequestBody CompanyProfileRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(jwt, request));
    }

    // multipart/form-data with two fields: file (the JPG/PNG picture) and kind (LOGO or IMAGE).
    // The service reports a missing field itself, so the error has the usual {code, message} form.
    @PostMapping(path = "/api/v1/company-profile/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CompanyMediaUploadView> uploadMedia(@AuthenticationPrincipal Jwt jwt,
                                                              @RequestParam(required = false) CompanyMediaKind kind,
                                                              @RequestParam(required = false) MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.upload(jwt, kind, file));
    }

    // Any uploaded picture, for the editor and its preview. Browsers cannot add the token to <img src>, so the
    // editor downloads it with fetch and the Authorization header.
    @GetMapping("/api/v1/company-profile/media/{id}")
    public ResponseEntity<byte[]> getMedia(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CompanyMedia media = service.getMedia(jwt, id);
        return picture(ResponseEntity.ok().cacheControl(CacheControl.noStore()), media);
    }

    @GetMapping("/api/v1/public/company-profile")
    public ResponseEntity<PublicCompanyProfileView> getPublic() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getPublic());
    }

    // Only pictures that the saved page uses. The ETag lets a browser ask "has it changed?" and get an empty 304.
    @GetMapping("/api/v1/public/company-media/{id}")
    public ResponseEntity<byte[]> getPublicMedia(@PathVariable UUID id) {
        CompanyMedia media = service.getPublishedMedia(id);
        return picture(ResponseEntity.ok().cacheControl(PUBLIC_PICTURE_CACHE).eTag(media.getId().toString()), media);
    }

    private static ResponseEntity<byte[]> picture(ResponseEntity.BodyBuilder response, CompanyMedia media) {
        return response.contentType(MediaType.parseMediaType(media.getContentType()))
                // The browser must use the stored type and never guess another one (for example HTML) from the bytes.
                .header("X-Content-Type-Options", "nosniff")
                // Opened directly in a tab, the picture may not load anything else or run any script.
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(media.getData());
    }
}
