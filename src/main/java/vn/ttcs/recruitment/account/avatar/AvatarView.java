package vn.ttcs.recruitment.account.avatar;

import java.time.Instant;
import java.util.UUID;

/**
 * The reference information saved with an avatar, returned after an upload. The two URLs work for every internal
 * user (the owner included), so the frontend can use them on the profile page and on a shared interview calendar.
 */
public record AvatarView(UUID userId, String contentType, int sizeBytes, Instant updatedAt,
                         String imageUrl, String thumbnailUrl) {

    static AvatarView of(UUID userId, ProcessedAvatar avatar, Instant updatedAt) {
        String imageUrl = "/api/v1/accounts/" + userId + "/avatar";
        return new AvatarView(userId, ProcessedAvatar.CONTENT_TYPE, avatar.avatar().length, updatedAt,
                imageUrl, imageUrl + "?size=thumbnail");
    }
}
