package vn.ttcs.recruitment.kanban;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ApplicationBoardController {
    private final ApplicationBoardService service;

    public ApplicationBoardController(ApplicationBoardService service) { this.service = service; }

    @GetMapping("/api/v1/application-board")
    public ResponseEntity<ApplicationBoardView> board(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestParam(required = false) UUID positionId,
                                                     @RequestParam(required = false) UUID requisitionId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.board(jwt, positionId, requisitionId, page, size));
    }
}
