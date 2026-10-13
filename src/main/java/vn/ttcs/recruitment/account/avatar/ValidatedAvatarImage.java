package vn.ttcs.recruitment.account.avatar;

import java.awt.image.BufferedImage;

/**
 * An upload that passed every avatar check. It carries the already decoded pixels, so the caller does not decode
 * the same bytes a second time. A photo wider or taller than {@link AvatarImageValidator#MAX_DECODED_SIDE} is
 * reduced to at most that many pixels per side, keeping its proportions. Metadata such as EXIF/GPS was not read and
 * is not part of {@code image}.
 */
public record ValidatedAvatarImage(AvatarImageFormat format, BufferedImage image) {
}
