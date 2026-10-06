package vn.ttcs.recruitment.account.avatar;

/**
 * One stored picture as it is sent back to the browser: its media type (for example {@code image/png}) and its bytes.
 * A record compares arrays by reference, so compare the bytes with {@code Arrays.equals}.
 */
public record AvatarFile(String contentType, byte[] content) {
}
