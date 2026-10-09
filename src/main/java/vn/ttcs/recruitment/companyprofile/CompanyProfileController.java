package vn.ttcs.recruitment.companyprofile;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Company introduction page of the recruitment portal (story S2-09). The first three endpoints are HR's
// editor and need JOB_POSTINGS_WRITE_ALL; the last one is public and returns only what candidates may see.
@RestController
public class CompanyProfileController {
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

    @GetMapping("/api/v1/public/company-profile")
    public ResponseEntity<PublicCompanyProfileView> getPublic() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getPublic());
    }
}
