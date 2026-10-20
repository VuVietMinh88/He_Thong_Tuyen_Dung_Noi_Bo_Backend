package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Task 339: anonymous; ApplicationTrackingRequestFilter has already refused a query string, a body that is not JSON
// or above 8 KiB, and more concurrent tracking requests than allowed, and has read the whole body before taking its
// permit. No "consumes": the filter owns the 415.
@RestController
public class ApplicationTrackingController {
    private final ApplicationTrackingService service;

    public ApplicationTrackingController(ApplicationTrackingService service) { this.service = service; }

    @PostMapping(ApplicationTrackingRoutes.LOOKUP)
    public ResponseEntity<PublicApplicationView> lookup(@Valid @RequestBody ApplicationLookupRequest body,
                                                        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.lookup(body, request));
    }
}
