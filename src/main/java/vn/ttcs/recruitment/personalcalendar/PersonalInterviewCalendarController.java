package vn.ttcs.recruitment.personalcalendar;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.candidate.CvContentDisposition;

import java.time.Instant;
import java.util.UUID;

@RestController
public class PersonalInterviewCalendarController {
    private final PersonalInterviewCalendarService service;

    public PersonalInterviewCalendarController(PersonalInterviewCalendarService service) { this.service = service; }

    @GetMapping("/api/v1/interviews/me/calendar")
    public ResponseEntity<PersonalInterviewCalendar> calendar(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "false") boolean includePending,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.calendar(jwt, from, to, includePending, page, size));
    }

    @GetMapping("/api/v1/interviews/{id}/cv")
    public ResponseEntity<byte[]> cv(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var cv = service.cv(jwt, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(cv.contentType())).contentLength(cv.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, CvContentDisposition.header(cv.filename(), cv.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(cv.content());
    }
}
