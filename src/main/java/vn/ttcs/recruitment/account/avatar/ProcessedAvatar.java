package vn.ttcs.recruitment.account.avatar;

import org.springframework.http.MediaType;

/**
 * The two files kept for one avatar, both PNG: {@code avatar} is 256 x 256 pixels and {@code thumbnail} is 64 x 64
 * pixels (see {@link AvatarImageProcessor}). A record compares arrays by reference, so compare the bytes with
 * {@code Arrays.equals}.
 */
public record ProcessedAvatar(byte[] avatar, byte[] thumbnail) {
    public static final String CONTENT_TYPE = MediaType.IMAGE_PNG_VALUE;
}
