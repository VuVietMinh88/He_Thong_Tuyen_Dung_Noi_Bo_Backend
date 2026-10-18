package vn.ttcs.recruitment.kanban;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplicationBoardPositionsController {
    private final ApplicationBoardPositionsService service;

    public ApplicationBoardPositionsController(ApplicationBoardPositionsService service) { this.service = service; }

    @GetMapping("/api/v1/application-board/positions")
    public ResponseEntity<ApplicationBoardPositionsView> positions(@AuthenticationPrincipal Jwt jwt,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.positions(jwt, page, size));
    }
}
