package vn.ttcs.recruitment.companyprofile;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.PermissionService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CompanyProfileService {
    // The recruitment portal belongs to job postings. The page is shared by the whole company, so there is
    // nothing to scope: JOB_POSTINGS_WRITE_SCOPED (recruiters) is not enough to use the editor endpoints.
    private static final String REQUIRED_PERMISSION = "JOB_POSTINGS_WRITE_ALL";
    // Every save and every upload holds this PostgreSQL advisory lock until commit (see requireWriteAccess).
    public static final long WRITE_LOCK_KEY = 237L;

    private final CompanyProfileRepository profiles;
    private final CompanyMediaRepository media;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CompanyProfileService(CompanyProfileRepository profiles, CompanyMediaRepository media,
                                 AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
                                 PermissionService permissions, JdbcTemplate jdbc, Clock clock) {
        this.profiles = profiles;
        this.media = media;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CompanyProfileView get(Jwt jwt) {
        requireAccess(jwt);
        return profiles.findById(CompanyProfile.SINGLETON_ID).map(CompanyProfileView::from)
                .orElseThrow(CompanyProfileService::notFound);
    }

    // Validates like save() and builds the result with the same method as getPublic(), but writes nothing,
    // so HR sees exactly what candidates will see once the content is saved.
    @Transactional(readOnly = true)
    public PublicCompanyProfileView preview(Jwt jwt, CompanyProfileRequest request) {
        requireAccess(jwt);
        var usedMedia = requireUsableMedia(request.logoMediaId(), request.imageIds());
        return publicView(request.companyName(), request.tagline(), request.introduction(),
                request.logoMediaId(), request.imageIds(), usedMedia);
    }

    @Transactional
    public CompanyProfileView save(Jwt jwt, CompanyProfileRequest request) {
        UUID actorId = requireWriteAccess(jwt);
        requireUsableMedia(request.logoMediaId(), request.imageIds());
        Instant now = now();
        var existing = profiles.findById(CompanyProfile.SINGLETON_ID);
        CompanyProfile profile;
        if (existing.isPresent()) {
            profile = existing.get();
            profile.update(request.companyName(), request.tagline(), request.introduction(),
                    request.logoMediaId(), request.imageIds(), actorId, now);
        } else {
            profile = profiles.save(new CompanyProfile(request.companyName(), request.tagline(),
                    request.introduction(), request.logoMediaId(), request.imageIds(), actorId, now));
        }
        return CompanyProfileView.from(profile);
    }

    // Anyone may read the saved page, without logging in. One snapshot for the page and its pictures.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicCompanyProfileView getPublic() {
        CompanyProfile profile = profiles.findById(CompanyProfile.SINGLETON_ID)
                .orElseThrow(CompanyProfileService::notFound);
        // V11 foreign keys guarantee that every saved picture exists and has the right kind.
        var usedMedia = loadMedia(profile.getLogoMediaId(), profile.getImageIds());
        return publicView(profile.getCompanyName(), profile.getTagline(), profile.getIntroduction(),
                profile.getLogoMediaId(), profile.getImageIds(), usedMedia);
    }

    // Stores one checked JPEG/PNG picture. Candidates see it only after a save puts it on the page.
    @Transactional
    public CompanyMediaUploadView upload(Jwt jwt, CompanyMediaKind kind, MultipartFile file) {
        if (kind == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Vui lòng chọn loại ảnh: LOGO (logo) hoặc IMAGE (ảnh giới thiệu).");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Vui lòng chọn một tệp ảnh JPG hoặc PNG.");
        }
        // Decoding a large picture takes a moment, so it runs before the locks below are taken.
        byte[] data = readBytes(file);
        var image = CompanyImageInspector.inspect(data);
        UUID actorId = requireWriteAccess(jwt);
        CompanyMedia saved = media.save(new CompanyMedia(kind, image.contentType(), data, image.width(),
                image.height(), actorId, now()));
        return CompanyMediaUploadView.from(saved);
    }

    // HR's editor may show any uploaded picture, also one that is not on the saved page yet (preview).
    @Transactional(readOnly = true)
    public CompanyMedia getMedia(Jwt jwt, UUID id) {
        requireAccess(jwt);
        return media.findById(id).orElseThrow(CompanyProfileService::mediaNotFound);
    }

    // Candidates only get pictures that the saved page uses. A picture that is uploaded but not saved on the
    // page yet, or was removed from the page, answers 404 exactly like a picture that does not exist.
    @Transactional(readOnly = true)
    public CompanyMedia getPublishedMedia(UUID id) {
        return media.findPublished(id).orElseThrow(CompanyProfileService::mediaNotFound);
    }

    private static PublicCompanyProfileView publicView(String companyName, String tagline, String introduction,
                                                       UUID logoId, List<UUID> imageIds,
                                                       Map<UUID, CompanyMediaSummary> usedMedia) {
        CompanyMediaView logo = logoId == null ? null : CompanyMediaView.from(usedMedia.get(logoId));
        List<CompanyMediaView> images = imageIds.stream()
                .map(imageId -> CompanyMediaView.from(usedMedia.get(imageId))).toList();
        return new PublicCompanyProfileView(companyName, tagline, introduction, logo, images);
    }

    // The logo must be an uploaded LOGO picture and every gallery item an uploaded IMAGE picture.
    // Checking here gives a clear 400 instead of a database foreign key error.
    private Map<UUID, CompanyMediaSummary> requireUsableMedia(UUID logoId, List<UUID> imageIds) {
        var found = loadMedia(logoId, imageIds);
        if (logoId != null && !hasKind(found.get(logoId), CompanyMediaKind.LOGO)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPANY_LOGO",
                    "Logo không tồn tại hoặc không phải ảnh được tải lên làm logo.");
        }
        for (UUID imageId : imageIds) {
            if (!hasKind(found.get(imageId), CompanyMediaKind.IMAGE)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPANY_IMAGE",
                        "Ảnh giới thiệu không tồn tại hoặc không phải ảnh được tải lên làm ảnh giới thiệu.");
            }
        }
        return found;
    }

    private Map<UUID, CompanyMediaSummary> loadMedia(UUID logoId, List<UUID> imageIds) {
        Set<UUID> ids = new HashSet<>(imageIds);
        if (logoId != null) {
            ids.add(logoId);
        }
        Map<UUID, CompanyMediaSummary> found = new HashMap<>();
        if (!ids.isEmpty()) {
            media.findSummaries(ids).forEach(summary -> found.put(summary.id(), summary));
        }
        return found;
    }

    private static boolean hasKind(CompanyMediaSummary summary, CompanyMediaKind kind) {
        return summary != null && summary.kind() == kind;
    }

    private void requireAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        requirePermission(actor.getId());
    }

    private UUID requireWriteAccess(Jwt jwt) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Same lock order as the other write services: the actor's account first, then the actor's session.
        // Role, lock and logout changes wait for these locks, so they cannot interleave with this write.
        accounts.findByIdForUpdate(actorId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        // Writes run one at a time. Before the first save there is no row to lock, so without this lock two
        // first saves could both try to insert the single row and one would fail with a database error.
        // Uploads take the same short lock only to share this method; they never touch the page row.
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (row, number) -> 0, WRITE_LOCK_KEY);

        // The request may have waited for those locks. Recheck the token, session and permission now.
        var now = clock.instant();
        requireUnexpiredToken(jwt, now);
        if (!session.getUserId().equals(actorId) || !session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        requirePermission(actorId);
        return actorId;
    }

    private void requirePermission(UUID actorId) {
        if (!permissions.forUser(actorId).contains(REQUIRED_PERMISSION)) {
            throw new AccessDeniedException("Company profile management requires " + REQUIRED_PERMISSION);
        }
    }

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so write responses show the same time a later GET reads.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // The multipart limit in application.properties already stops files above 5 MB before they reach memory.
    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "COMPANY_PROFILE_NOT_FOUND",
                "Trang giới thiệu công ty chưa được thiết lập.");
    }

    private static ApiException mediaNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "COMPANY_MEDIA_NOT_FOUND", "Không tìm thấy ảnh.");
    }
}
