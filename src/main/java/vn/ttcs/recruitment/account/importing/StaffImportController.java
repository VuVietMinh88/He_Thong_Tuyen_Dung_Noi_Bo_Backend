package vn.ttcs.recruitment.account.importing;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/accounts/import")
public class StaffImportController {
    private final StaffImportService service;

    public StaffImportController(StaffImportService service) {
        this.service = service;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@AuthenticationPrincipal Jwt jwt) {
        byte[] workbook = service.createTemplate(jwt);
        ContentDisposition attachment = ContentDisposition.attachment().filename(StaffImportTemplate.FILE_NAME).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(StaffImportTemplate.CONTENT_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment.toString())
                .body(workbook);
    }

    // The file is optional here so that a request without it gets the import's own 400 message
    // (IMPORT_FILE_REQUIRED) from the service instead of a generic multipart error.
    @PostMapping("/preview")
    public ResponseEntity<StaffImportPreview> preview(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "file", required = false) MultipartFile file) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(jwt, file));
    }

    // Creates the accounts and returns the summary report. Same "file" field as the preview; it is checked again.
    @PostMapping
    public ResponseEntity<StaffImportReport> importStaff(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "file", required = false) MultipartFile file) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.importStaff(jwt, file));
    }
}
