package vn.ttcs.recruitment.account.avatar;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
public class AvatarController {
    private final AvatarService service;

    public AvatarController(AvatarService service) {
        this.service = service;
    }

    // The file is optional here so that a request without it gets AVATAR_FILE_REQUIRED from the service instead
    // of a generic multipart error.
    @PutMapping("/api/v1/profile/avatar")
    public ResponseEntity<AvatarView> replace(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(name = "file", required = false) MultipartFile file) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replace(jwt, file));
    }

    @DeleteMapping("/api/v1/profile/avatar")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        service.delete(jwt);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/v1/profile/avatar")
    public ResponseEntity<byte[]> getOwn(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(defaultValue = "full") String size) {
        return image(service.getOwn(jwt, AvatarSize.fromParameter(size)));
    }

    @GetMapping("/api/v1/accounts/{id}/avatar")
    public ResponseEntity<byte[]> getForAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                @RequestParam(defaultValue = "full") String size) {
        return image(service.getForAccount(jwt, id, AvatarSize.fromParameter(size)));
    }

    private static ResponseEntity<byte[]> image(AvatarFile file) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
