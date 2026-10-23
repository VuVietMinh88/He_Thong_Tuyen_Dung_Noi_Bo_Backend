package vn.ttcs.recruitment.candidate;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Task 362 (story S4-09): one application's CV at the frontend's path (candidateProfileService, task 359). The matcher
// in SecurityConfiguration admits CANDIDATES_READ_ALL and CANDIDATES_READ_SCOPED; ApplicationCvService checks the
// permission again, then the scope of this application, and logs the read. A malformed {id} fails as 400
// VALIDATION_ERROR in ApiExceptionHandler.
//
// The type is the stored one (never guessed from the name or the bytes), a PDF is inline and a DOCX an attachment
// (CvContentDisposition). nosniff, a sandboxing CSP and no-store protect a direct opening of the URL (a download
// manager, a future cookie session). The frontend fetches the bytes with the bearer token into a Blob and shows a
// blob: URL, which inherits the frontend page's policy instead: there, the safety rests on the Blob keeping this type,
// on showing only a PDF inline, and on CvValidator refusing active content at upload.
@RestController
public class ApplicationCvController {
    private final ApplicationCvService service;

    public ApplicationCvController(ApplicationCvService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/applications/{id}/cv")
    public ResponseEntity<byte[]> cv(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CvFile cv = service.cv(jwt, id);
        byte[] content = cv.content();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(cv.contentType()))
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, CvContentDisposition.header(cv.filename(), cv.contentType()))
                // The browser must use the stored type and never guess another one (for example HTML) from the bytes.
                .header("X-Content-Type-Options", "nosniff")
                // Opened directly in a tab, the file may not load anything else or run any script.
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(content);
    }
}
