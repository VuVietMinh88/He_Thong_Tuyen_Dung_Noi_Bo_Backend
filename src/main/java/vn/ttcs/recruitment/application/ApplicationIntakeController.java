package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class ApplicationIntakeController {
    private final ApplicationIntakeService service;
    public ApplicationIntakeController(ApplicationIntakeService service) { this.service = service; }

    @PostMapping(value = ApplicationIntakeRoutes.SUBMISSION, consumes = "multipart/form-data")
    public ResponseEntity<AcceptedApplication> submit(@PathVariable UUID id, HttpServletRequest request) {
        var receipt = service.submit(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new AcceptedApplication(receipt.applicationId(), "RECEIVED", "Đã nhận", receipt.receivedAt(),
                        receipt.trackingCode(), receipt.trackingUrl()));
    }

    @GetMapping(ApplicationIntakeRoutes.SOURCES)
    public ResponseEntity<List<ApplicationIntakeService.SourceOption>> sources() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sources());
    }

    public record AcceptedApplication(UUID applicationId, String status, String statusName, Instant receivedAt,
                                      String trackingCode, String trackingUrl) {
        @Override public String toString() { return "AcceptedApplication[tracking data redacted]"; }
    }
}
