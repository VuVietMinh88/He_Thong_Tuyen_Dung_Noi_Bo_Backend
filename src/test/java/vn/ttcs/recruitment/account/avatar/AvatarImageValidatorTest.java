package vn.ttcs.recruitment.account.avatar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static java.awt.image.BufferedImage.TYPE_BYTE_GRAY;
import static java.awt.image.BufferedImage.TYPE_BYTE_INDEXED;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;
import static java.awt.image.BufferedImage.TYPE_INT_RGB;
import static java.awt.image.BufferedImage.TYPE_USHORT_GRAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AvatarImageValidatorTest {
    private static final int TWO_MEGABYTES = 2 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int NO_FILTER = 0;
    private static final Color RED = new Color(200, 40, 40);
    private static final Color BLUE = new Color(0x33, 0x99, 0xCC);

    private final AvatarImageValidator validator = new AvatarImageValidator();

    @Test
    void acceptsPngAndReturnsEveryDecodedPixel() {
        BufferedImage original = noise(120, 80, TYPE_INT_ARGB);

        ValidatedAvatarImage result = validator.validate(encode(original, "png"));

        assertThat(result.format()).isEqualTo(AvatarImageFormat.PNG);
        assertThat(result.image().getWidth()).isEqualTo(120);
        assertThat(result.image().getHeight()).isEqualTo(80);
        assertThat(pixels(result.image())).containsExactly(pixels(original));
    }

    @Test
    void acceptsJpegAndReturnsTheDecodedColour() {
        ValidatedAvatarImage result = validator.validate(encode(filled(90, 60, RED), "jpeg"));

        assertThat(result.format()).isEqualTo(AvatarImageFormat.JPEG);
        assertThat(result.image().getWidth()).isEqualTo(90);
        assertThat(result.image().getHeight()).isEqualTo(60);
        assertCloseToRed(result.image().getRGB(45, 30));
    }

    static Stream<Arguments> commonColourModels() {
        return Stream.of(
                Arguments.of("grayscale JPEG", TYPE_BYTE_GRAY, "jpeg", AvatarImageFormat.JPEG),
                Arguments.of("RGB PNG", TYPE_INT_RGB, "png", AvatarImageFormat.PNG),
                Arguments.of("grayscale PNG", TYPE_BYTE_GRAY, "png", AvatarImageFormat.PNG),
                Arguments.of("16-bit grayscale PNG", TYPE_USHORT_GRAY, "png", AvatarImageFormat.PNG),
                Arguments.of("palette PNG", TYPE_BYTE_INDEXED, "png", AvatarImageFormat.PNG));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commonColourModels")
    void acceptsCommonColourModels(String label, int imageType, String writerFormat, AvatarImageFormat expected) {
        ValidatedAvatarImage result = validator.validate(encode(noise(33, 21, imageType), writerFormat));

        assertThat(result.format()).isEqualTo(expected);
        assertThat(result.image().getWidth()).isEqualTo(33);
        assertThat(result.image().getHeight()).isEqualTo(21);
    }

    @Test
    void acceptsExactlyTwoMegabytesAndRejectsOneByteMore() {
        byte[] exactlyTwoMegabytes = pngPaddedTo(TWO_MEGABYTES);
        byte[] oneByteMore = pngPaddedTo(TWO_MEGABYTES + 1);

        assertThat(exactlyTwoMegabytes).hasSize(TWO_MEGABYTES);
        assertThat(validator.validate(exactlyTwoMegabytes).format()).isEqualTo(AvatarImageFormat.PNG);
        assertRejected(oneByteMore, HttpStatus.CONTENT_TOO_LARGE, "AVATAR_TOO_LARGE");
    }

    @Test
    void checksTheSizeBeforeLookingAtTheContent() {
        // Zero bytes are not an image at all, yet the cheaper size check answers first.
        assertRejected(new byte[TWO_MEGABYTES + 1], HttpStatus.CONTENT_TOO_LARGE, "AVATAR_TOO_LARGE");
        assertRejected(new byte[5 * 1024 * 1024], HttpStatus.CONTENT_TOO_LARGE, "AVATAR_TOO_LARGE");
    }

    @Test
    void rejectsMissingOrEmptyFile() {
        assertRejected(null, HttpStatus.BAD_REQUEST, "AVATAR_INVALID");
        assertRejected(new byte[0], HttpStatus.BAD_REQUEST, "AVATAR_INVALID");
    }

    static Stream<Arguments> unsupportedFiles() {
        BufferedImage image = noise(32, 32, TYPE_INT_RGB);
        return Stream.of(
                Arguments.of("GIF bytes (for example renamed to avatar.jpg)", encode(image, "gif")),
                Arguments.of("BMP bytes (for example renamed to avatar.png)", encode(image, "bmp")),
                Arguments.of("plain text", ascii("Day khong phai la anh dai dien")),
                Arguments.of("SVG", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>")),
                Arguments.of("PDF", ascii("%PDF-1.7\n%...")),
                Arguments.of("WebP", ascii("RIFF\0\0\0\0WEBPVP8 ")),
                Arguments.of("only the first two JPEG bytes", new byte[] {(byte) 0xFF, (byte) 0xD8}),
                Arguments.of("PNG signature with one wrong byte",
                        new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, 0x00, 0, 0, 0, 13}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedFiles")
    void rejectsEverythingThatIsNotJpegOrPng(String label, byte[] content) {
        assertRejected(content, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AVATAR_TYPE_UNSUPPORTED");
    }

    static Stream<Arguments> damagedFiles() {
        byte[] jpeg = encode(noise(200, 150, TYPE_INT_RGB), "jpeg");
        byte[] png = encode(noise(200, 150, TYPE_INT_ARGB), "png");
        byte[] largeJpeg = encode(filled(2048, 1536, RED), "jpeg");
        byte[] largePng = flat16BitPng(2048, 1536, BLUE, NO_FILTER);
        return Stream.of(
                Arguments.of("large JPEG (decoded at reduced size) cut in half",
                        Arrays.copyOf(largeJpeg, largeJpeg.length / 2)),
                Arguments.of("large PNG (decoded at reduced size) cut in half",
                        Arrays.copyOf(largePng, largePng.length / 2)),
                Arguments.of("JPEG cut in half", Arrays.copyOf(jpeg, jpeg.length / 2)),
                Arguments.of("JPEG without its end marker", Arrays.copyOf(jpeg, jpeg.length - 2)),
                Arguments.of("JPEG signature only", Arrays.copyOf(jpeg, 3)),
                Arguments.of("JPEG with zeroed bytes in the middle", zeroMiddle(jpeg)),
                Arguments.of("JPEG signature followed by PNG data", concat(Arrays.copyOf(jpeg, 3), png)),
                Arguments.of("PNG cut in half", Arrays.copyOf(png, png.length / 2)),
                Arguments.of("PNG signature only", Arrays.copyOf(png, 8)),
                Arguments.of("PNG with zeroed bytes in the middle", zeroMiddle(png)),
                Arguments.of("PNG signature followed by JPEG data", concat(Arrays.copyOf(png, 8), jpeg)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damagedFiles")
    void rejectsTruncatedOrCorruptImages(String label, byte[] content) {
        assertRejected(content, HttpStatus.BAD_REQUEST, "AVATAR_INVALID");
    }

    @Test
    void acceptsJpegWithExtraBytesAfterTheEndMarker() {
        // Some phones append their own data after the JPEG end marker; the picture itself is complete.
        byte[] jpeg = encode(noise(40, 30, TYPE_INT_RGB), "jpeg");

        ValidatedAvatarImage result = validator.validate(concat(jpeg, ascii("vendor trailer data")));

        assertThat(result.format()).isEqualTo(AvatarImageFormat.JPEG);
        assertThat(result.image().getWidth()).isEqualTo(40);
    }

    @Test
    void rejectsHugeDeclaredDimensionsBeforeDecodingThePixels() {
        byte[] png = encode(noise(16, 16, TYPE_INT_RGB), "png");
        byte[] jpeg = encode(noise(16, 16, TYPE_INT_RGB), "jpeg");

        // Only the header claims 30000 x 30000; the pixel data is still 16 x 16.
        assertRejected(withPngSize(png, 30_000, 30_000), HttpStatus.BAD_REQUEST, "AVATAR_DIMENSIONS_TOO_LARGE");
        assertRejected(withJpegSize(jpeg, 30_000, 30_000), HttpStatus.BAD_REQUEST, "AVATAR_DIMENSIONS_TOO_LARGE");
        // Control: the same lie within the limit reaches the decoder and fails there, which shows the result above
        // came from the header check and not from decoding.
        assertRejected(withPngSize(png, 64, 64), HttpStatus.BAD_REQUEST, "AVATAR_INVALID");
        assertRejected(withJpegSize(jpeg, 64, 64), HttpStatus.BAD_REQUEST, "AVATAR_INVALID");
    }

    @Test
    void acceptsTheMaximumSideLengthAndRejectsOnePixelMore() {
        assertThat(validator.validate(encode(noise(4096, 2, TYPE_INT_RGB), "png")).format())
                .isEqualTo(AvatarImageFormat.PNG);
        assertThat(validator.validate(encode(noise(2, 4096, TYPE_INT_RGB), "png")).format())
                .isEqualTo(AvatarImageFormat.PNG);
        assertRejected(encode(noise(4097, 2, TYPE_INT_RGB), "png"),
                HttpStatus.BAD_REQUEST, "AVATAR_DIMENSIONS_TOO_LARGE");
        assertRejected(encode(noise(2, 4097, TYPE_INT_RGB), "jpeg"),
                HttpStatus.BAD_REQUEST, "AVATAR_DIMENSIONS_TOO_LARGE");
    }

    static Stream<Arguments> decodedSizes() {
        return Stream.of(
                Arguments.of(1024, 700, 1024, 700), // small enough: every pixel is kept
                Arguments.of(1025, 700, 513, 350), // every 2nd pixel
                Arguments.of(3000, 2000, 1000, 667), // every 3rd pixel
                Arguments.of(700, 4096, 175, 1024)); // every 4th pixel, portrait
    }

    @ParameterizedTest(name = "{0} x {1} is decoded as {2} x {3}")
    @MethodSource("decodedSizes")
    void decodesLargeImagesAtMost1024PixelsPerSide(int width, int height, int decodedWidth, int decodedHeight) {
        ValidatedAvatarImage result = validator.validate(flat16BitPng(width, height, BLUE, NO_FILTER));

        assertThat(result.image().getWidth()).isEqualTo(decodedWidth);
        assertThat(result.image().getHeight()).isEqualTo(decodedHeight);
        assertThat(result.image().getRGB(decodedWidth - 1, decodedHeight - 1)).isEqualTo(BLUE.getRGB());
    }

    @Test
    void smallFileDeclaringTheMaximumSizeDoesNotNeedMuchMemory() {
        // 4096 x 4096 with 16-bit colour and transparency would need 128MB once decoded, yet the file is tiny.
        byte[] png = flat16BitPng(4096, 4096, BLUE, NO_FILTER);
        assertThat(png.length).isLessThan(300 * 1024);

        BufferedImage image = validator.validate(png).image();

        assertThat(image.getWidth()).isEqualTo(1024);
        assertThat(image.getHeight()).isEqualTo(1024);
        // 4 values (red, green, blue, alpha) per kept pixel: 16 times fewer than at full size.
        assertThat(image.getRaster().getDataBuffer().getSize()).isEqualTo(1024 * 1024 * 4);
        assertThat(image.getRGB(0, 0)).isEqualTo(BLUE.getRGB());
        assertThat(image.getRGB(1023, 1023)).isEqualTo(BLUE.getRGB());
    }

    @Test
    void decodesLargeJpegAtReducedSizeToo() {
        ValidatedAvatarImage result = validator.validate(encode(filled(2048, 1536, RED), "jpeg"));

        assertThat(result.format()).isEqualTo(AvatarImageFormat.JPEG);
        assertThat(result.image().getWidth()).isEqualTo(1024);
        assertThat(result.image().getHeight()).isEqualTo(768);
        assertCloseToRed(result.image().getRGB(512, 384));
    }

    @Test
    void stillChecksThePixelsThatAreLeftOutOfTheReducedImage() {
        // 4095 rows are read with every 4th row kept (0, 4, ..., 4092), so the last row (4094) is not kept.
        byte[] intact = flat16BitPng(4095, 4095, BLUE, NO_FILTER);
        byte[] brokenLastRow = flat16BitPng(4095, 4095, BLUE, 9); // PNG only defines row filter types 0 to 4

        assertThat(validator.validate(intact).image().getHeight()).isEqualTo(1024);
        assertRejected(brokenLastRow, HttpStatus.BAD_REQUEST, "AVATAR_INVALID");
    }

    private void assertRejected(byte[] content, HttpStatus status, String code) {
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(status);
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.getMessage()).isNotBlank();
                });
    }

    private static BufferedImage noise(int width, int height, int imageType) {
        BufferedImage image = new BufferedImage(width, height, imageType);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }

    private static BufferedImage filled(int width, int height, Color colour) {
        BufferedImage image = new BufferedImage(width, height, TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(colour);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    // JPEG is lossy, so the decoded colour only has to be close to RED.
    private static void assertCloseToRed(int rgb) {
        Color colour = new Color(rgb);
        assertThat(colour.getRed()).isCloseTo(RED.getRed(), within(6));
        assertThat(colour.getGreen()).isCloseTo(RED.getGreen(), within(6));
        assertThat(colour.getBlue()).isCloseTo(RED.getBlue(), within(6));
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static byte[] encode(BufferedImage image, String format) {
        var output = new ByteArrayOutputStream();
        // A memory stream keeps ImageIO from writing temporary cache files.
        try (var stream = new MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(image, format, stream)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return output.toByteArray();
    }

    // Inserts a private ancillary chunk ("paDd") right after IHDR. Decoders skip such chunks, so the result is still
    // a valid PNG whose total size is exactly totalSize bytes.
    private static byte[] pngPaddedTo(int totalSize) {
        byte[] png = encode(noise(64, 64, TYPE_INT_RGB), "png");
        int afterHeader = 8 + 25; // 8-byte signature + IHDR chunk (length, type, 13 data bytes, CRC)
        byte[] padding = chunk("paDd", new byte[totalSize - png.length - 12]);
        return concat(Arrays.copyOf(png, afterHeader), padding, Arrays.copyOfRange(png, afterHeader, png.length));
    }

    // Writes a PNG by hand with 16 bits per channel plus transparency, the most memory per pixel PNG allows. One colour
    // everywhere compresses even 4096 x 4096 to about 200KB, and the pixels are written row by row, so the test itself
    // never holds the full image in memory. Every row starts with its filter type; lastRowFilter sets the last one.
    private static byte[] flat16BitPng(int width, int height, Color colour, int lastRowFilter) {
        byte[] row = new byte[1 + width * 8];
        for (int x = 0; x < width; x++) {
            // 8-bit value v as 16-bit is v * 257 (0xCC -> 0xCCCC), so it decodes back to exactly the same colour.
            ByteBuffer.wrap(row, 1 + x * 8, 8)
                    .putShort((short) (colour.getRed() * 257))
                    .putShort((short) (colour.getGreen() * 257))
                    .putShort((short) (colour.getBlue() * 257))
                    .putShort((short) 0xFFFF); // fully opaque
        }
        var pixelData = new ByteArrayOutputStream();
        try (var deflater = new DeflaterOutputStream(pixelData)) {
            for (int y = 0; y < height; y++) {
                row[0] = (byte) (y == height - 1 ? lastRowFilter : NO_FILTER);
                deflater.write(row);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        byte[] header = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 16) // bits per channel
                .put((byte) 6) // colour type 6 = red, green, blue and alpha
                .put(new byte[] {0, 0, 0}) // standard compression, standard filtering, not interlaced
                .array();
        return concat(PNG_SIGNATURE, chunk("IHDR", header), chunk("IDAT", pixelData.toByteArray()),
                chunk("IEND", new byte[0]));
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data)
                .putInt((int) crc.getValue()).array();
    }

    // Rewrites width/height in the PNG IHDR chunk and recomputes its CRC, so only the declared size is a lie.
    private static byte[] withPngSize(byte[] png, int width, int height) {
        byte[] copy = png.clone();
        ByteBuffer buffer = ByteBuffer.wrap(copy);
        buffer.putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(copy, 12, 17); // chunk type + 13 data bytes
        buffer.putInt(29, (int) crc.getValue());
        return copy;
    }

    // Walks the JPEG segments to the baseline frame header (SOF0) and rewrites its height and width.
    private static byte[] withJpegSize(byte[] jpeg, int width, int height) {
        byte[] copy = jpeg.clone();
        ByteBuffer buffer = ByteBuffer.wrap(copy);
        int offset = 2; // skip the start-of-image marker
        while ((copy[offset + 1] & 0xFF) != 0xC0) {
            offset += 2 + (buffer.getShort(offset + 2) & 0xFFFF);
        }
        buffer.putShort(offset + 5, (short) height).putShort(offset + 7, (short) width);
        return copy;
    }

    private static byte[] zeroMiddle(byte[] content) {
        byte[] copy = content.clone();
        Arrays.fill(copy, copy.length / 2, copy.length / 2 + 200, (byte) 0);
        return copy;
    }

    private static byte[] concat(byte[]... parts) {
        var output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
