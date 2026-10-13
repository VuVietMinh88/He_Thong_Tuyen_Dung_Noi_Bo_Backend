package vn.ttcs.recruitment.account.avatar;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

/** Which stored picture a GET returns: {@code ?size=full} (256 x 256, the default) or {@code ?size=thumbnail} (64 x 64). */
public enum AvatarSize {
    FULL,
    THUMBNAIL;

    public static AvatarSize fromParameter(String value) {
        return switch (value) {
            case "full" -> FULL;
            case "thumbnail" -> THUMBNAIL;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Tham số size chỉ nhận giá trị full hoặc thumbnail.");
        };
    }
}
