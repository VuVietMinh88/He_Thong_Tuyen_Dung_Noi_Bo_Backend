package vn.ttcs.recruitment.account.avatar;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Turns an upload that passed {@link AvatarImageValidator} into the two pictures that are kept:
 * <ol>
 *     <li>cut the largest square from the middle of the picture;</li>
 *     <li>resize that square to a 256 x 256 avatar (a smaller picture is enlarged);</li>
 *     <li>shrink the avatar to a 64 x 64 thumbnail.</li>
 * </ol>
 * Both are written as new PNG files from the pixels only, so nothing else from the uploaded file (EXIF, GPS,
 * comments, extra bytes) is kept. PNG is lossless and keeps transparency. EXIF rotation is not applied because the
 * validator does not read metadata.
 */
@Component
public class AvatarImageProcessor {
    public static final int AVATAR_SIZE = 256;
    public static final int THUMBNAIL_SIZE = 64;

    public ProcessedAvatar process(ValidatedAvatarImage validated) {
        BufferedImage source = validated.image();
        // A picture with transparency (for example a logo on a transparent background) stays transparent. A picture
        // without it is stored as plain RGB, which gives a smaller file.
        int imageType = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage square = cropCenterSquare(source, imageType);
        BufferedImage avatar = resize(square, AVATAR_SIZE);
        BufferedImage thumbnail = resize(avatar, THUMBNAIL_SIZE);
        return new ProcessedAvatar(encodePng(avatar), encodePng(thumbnail));
    }

    // Keeps the middle of a wide or tall picture, where the face usually is. When the extra length is odd, the
    // remaining pixel is cut from the right (or bottom) side.
    private static BufferedImage cropCenterSquare(BufferedImage source, int imageType) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - side) / 2;
        int top = (source.getHeight() - side) / 2;
        if (isGrey(source)) {
            return copyGreySquare(source, left, top, side, imageType);
        }
        // Copying at the same size moves the pixels unchanged. It also turns the other colour models the validator
        // accepts (palette, 16-bit colour) into 8-bit RGB or ARGB, and later resizing never sees the pixels outside
        // the square.
        return draw(source, left, top, side, side, imageType);
    }

    // Grey JPEG and PNG files are read with Java's built-in grey colour space. Java treats it as linear light and
    // lightens every shade when converting it to RGB (128 becomes 188): getRGB does that, and so does drawImage for
    // grey with transparency. In the file, grey 128 means the colour R = G = B = 128, so the numbers are copied as
    // they are.
    private static boolean isGrey(BufferedImage image) {
        ColorModel colourModel = image.getColorModel();
        return colourModel instanceof ComponentColorModel
                && colourModel.getColorSpace() == ColorSpace.getInstance(ColorSpace.CS_GRAY);
    }

    private static BufferedImage copyGreySquare(BufferedImage source, int left, int top, int side, int imageType) {
        ColorModel colourModel = source.getColorModel();
        Raster raster = source.getRaster(); // band 0 is the grey value, band 1 (if present) the alpha value
        BufferedImage target = new BufferedImage(side, side, imageType);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int grey = to8Bits(raster.getSample(left + x, top + y, 0), colourModel.getComponentSize(0));
                int alpha = colourModel.hasAlpha()
                        ? to8Bits(raster.getSample(left + x, top + y, 1), colourModel.getComponentSize(1))
                        : 255;
                target.setRGB(x, y, alpha << 24 | grey << 16 | grey << 8 | grey);
            }
        }
        return target;
    }

    // Scales an 8-bit or 16-bit value to 0..255, rounded to the nearest whole number (0x8080 of 0xFFFF gives 128).
    private static int to8Bits(int sample, int bits) {
        int max = (1 << bits) - 1;
        return (sample * 255 + max / 2) / max;
    }

    private static BufferedImage resize(BufferedImage square, int size) {
        BufferedImage result = square;
        // One bilinear step mixes only the 2 x 2 nearest pixels. Shrinking 1024 -> 256 in one step would therefore
        // ignore half of the rows and columns, and fine patterns would turn into stripes. Halving step by step makes
        // every pixel count: each halving is the exact average of 2 x 2 pixels.
        while (result.getWidth() / 2 >= size) {
            result = scale(result, result.getWidth() / 2);
        }
        // The last step shrinks by less than half, or enlarges a picture smaller than the target.
        if (result.getWidth() != size) {
            result = scale(result, size);
        }
        return result;
    }

    private static BufferedImage scale(BufferedImage square, int size) {
        return draw(square, 0, 0, square.getWidth(), size, square.getType());
    }

    // Draws the source square (left, top, side) into a new size x size picture of the given type.
    private static BufferedImage draw(BufferedImage source, int left, int top, int side, int size, int imageType) {
        BufferedImage target = new BufferedImage(size, size, imageType);
        Graphics2D graphics = target.createGraphics();
        try {
            // Src copies every pixel including its transparency, instead of blending it onto the empty target.
            graphics.setComposite(AlphaComposite.Src);
            // Java2D mixes transparent pixels by their opacity here, so a transparent edge does not turn dark.
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, size, size, left, top, left + side, top + side, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encodePng(BufferedImage image) {
        var output = new ByteArrayOutputStream();
        // A memory stream keeps ImageIO from writing temporary cache files to disk.
        try (var stream = new MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(image, "png", stream)) {
                throw new IllegalStateException("No ImageIO writer for PNG");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not encode the avatar as PNG", exception);
        }
        return output.toByteArray();
    }
}
