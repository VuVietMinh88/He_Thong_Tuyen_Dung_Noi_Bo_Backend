package vn.ttcs.recruitment.account.avatar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static java.awt.image.BufferedImage.TYPE_BYTE_GRAY;
import static java.awt.image.BufferedImage.TYPE_BYTE_INDEXED;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;
import static java.awt.image.BufferedImage.TYPE_INT_RGB;
import static java.awt.image.BufferedImage.TYPE_USHORT_GRAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AvatarImageProcessorTest {
    private static final Color RED = new Color(200, 40, 40);
    private static final Color BLUE = new Color(0x33, 0x99, 0xCC);
    private static final Color TOP_LEFT = new Color(250, 220, 0);
    private static final Color TOP_RIGHT = new Color(0, 150, 60);
    private static final Color BOTTOM_LEFT = new Color(30, 30, 30);
    private static final Color BOTTOM_RIGHT = new Color(240, 240, 240);

    private final AvatarImageValidator validator = new AvatarImageValidator();
    private final AvatarImageProcessor processor = new AvatarImageProcessor();

    @ParameterizedTest(name = "{0} x {1}")
    // Random pixels hardly compress, so the larger cases stay below the 2MB upload limit.
    @CsvSource({"400, 300", "300, 400", "256, 256", "900, 700", "1000, 600", "100, 40", "3, 7", "1, 1"})
    void createsA256AvatarAndA64ThumbnailFromAnyShape(int width, int height) {
        ProcessedAvatar result = processor.process(validator.validate(encode(noise(width, height, TYPE_INT_RGB), "png")));

        // The validator itself must accept what is stored: a PNG file of exactly the documented size.
        ValidatedAvatarImage avatar = validator.validate(result.avatar());
        ValidatedAvatarImage thumbnail = validator.validate(result.thumbnail());
        assertThat(avatar.format()).isEqualTo(AvatarImageFormat.PNG);
        assertThat(avatar.image().getWidth()).isEqualTo(256);
        assertThat(avatar.image().getHeight()).isEqualTo(256);
        assertThat(thumbnail.format()).isEqualTo(AvatarImageFormat.PNG);
        assertThat(thumbnail.image().getWidth()).isEqualTo(64);
        assertThat(thumbnail.image().getHeight()).isEqualTo(64);
        assertThat(ProcessedAvatar.CONTENT_TYPE).isEqualTo("image/png");
    }

    @ParameterizedTest(name = "{0} x {1} keeps the square starting at ({2}, {3})")
    @CsvSource({"300, 256, 22, 0", "301, 256, 22, 0", "256, 301, 0, 22", "256, 256, 0, 0"})
    void cutsExactlyTheMiddleSquare(int width, int height, int left, int top) {
        // A 256-pixel square needs no resizing, so every avatar pixel must equal one pixel of the original.
        BufferedImage original = noise(width, height, TYPE_INT_RGB);

        BufferedImage avatar = decode(processor.process(validator.validate(encode(original, "png"))).avatar());

        assertThat(pixels(avatar)).containsExactly(original.getRGB(left, top, 256, 256, null, 0, 256));
    }

    @ParameterizedTest(name = "{0} x {1} keeps the square starting at ({2}, {3})")
    @CsvSource({"301, 256, 22, 0", "256, 301, 0, 22"})
    void cutsExactlyTheMiddleSquareOfAGreyPictureWithTransparency(int width, int height, int left, int top) {
        // Grey pictures are copied value by value instead of being drawn, so their cut is checked separately.
        var colourModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false,
                Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        WritableRaster original = colourModel.createCompatibleWritableRaster(width, height);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                original.setSample(x, y, 0, random.nextInt(256)); // grey
                original.setSample(x, y, 1, random.nextInt(256)); // alpha
            }
        }
        byte[] upload = encode(new BufferedImage(colourModel, original, false, null), "png");

        BufferedImage avatar = decode(processor.process(validator.validate(upload)).avatar());

        int[] expected = new int[256 * 256];
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int grey = original.getSample(left + x, top + y, 0);
                int alpha = original.getSample(left + x, top + y, 1);
                expected[y * 256 + x] = alpha << 24 | grey << 16 | grey << 8 | grey;
            }
        }
        assertThat(pixels(avatar)).containsExactly(expected);
    }

    static Stream<Arguments> picturesWithSideBands() {
        return Stream.of(
                // The middle 200 x 200 square has one colour per quarter; the parts outside it are red and blue.
                // 401 extra pixels: 200 are cut on the left (or top) and 201 on the right (or bottom).
                Arguments.of("wide", bands(601, 200, 200, 0)),
                Arguments.of("tall", bands(200, 601, 0, 200)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("picturesWithSideBands")
    void keepsTheMiddleOfAWideOrTallPicture(String label, BufferedImage original) {
        ProcessedAvatar result = processor.process(validator.validate(encode(original, "png")));

        // The 200-pixel square is enlarged to 256: one red or blue column or row left in the square would colour the
        // nearest corners, so the corners show that the cut is exact.
        assertQuartersAreKept(decode(result.avatar()));
        assertQuartersAreKept(decode(result.thumbnail()));
    }

    @Test
    void eachShrunkPixelIsTheAverageOfThePixelsItReplaces() {
        BufferedImage original = noise(512, 512, TYPE_INT_RGB);

        ProcessedAvatar result = processor.process(validator.validate(encode(original, "png")));

        BufferedImage avatar = decode(result.avatar());
        assertEveryPixelIsTheBlockAverage(original, avatar, 1); // 512 -> 256: 2 x 2 pixels each
        assertEveryPixelIsTheBlockAverage(avatar, decode(result.thumbnail()), 2); // 256 -> 64: 4 x 4 pixels each
    }

    @Test
    void finePatternsBecomeAnEvenGreyInsteadOfStripes() {
        // Columns repeat white, black, black, white. Their average is mid grey. Keeping only some columns (what a
        // single-step shrink from 1024 to 256 does) would give black or white stripes instead.
        BufferedImage stripes = new BufferedImage(1024, 1024, TYPE_INT_RGB);
        for (int y = 0; y < 1024; y++) {
            for (int x = 0; x < 1024; x++) {
                stripes.setRGB(x, y, x % 4 == 1 || x % 4 == 2 ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }

        ProcessedAvatar result = processor.process(validator.validate(encode(stripes, "png")));

        assertEveryPixelIsGrey(decode(result.avatar()), 128);
        assertEveryPixelIsGrey(decode(result.thumbnail()), 128);
    }

    static Stream<Arguments> colourModels() {
        return Stream.of(
                Arguments.of("JPEG photo", encode(noise(90, 60, TYPE_INT_RGB), "jpeg"), false),
                Arguments.of("grayscale JPEG", encode(noise(90, 60, TYPE_BYTE_GRAY), "jpeg"), false),
                Arguments.of("RGB PNG", encode(noise(90, 60, TYPE_INT_RGB), "png"), false),
                Arguments.of("grayscale PNG", encode(noise(90, 60, TYPE_BYTE_GRAY), "png"), false),
                Arguments.of("16-bit grayscale PNG", encode(noise(90, 60, TYPE_USHORT_GRAY), "png"), false),
                Arguments.of("palette PNG", encode(noise(90, 60, TYPE_BYTE_INDEXED), "png"), false),
                Arguments.of("PNG with transparency", encode(noise(90, 60, TYPE_INT_ARGB), "png"), true),
                Arguments.of("16-bit PNG with transparency", encode(noise16BitWithAlpha(90, 60), "png"), true),
                Arguments.of("palette PNG with a transparent colour", encode(paletteWithTransparentColour(), "png"),
                        true),
                Arguments.of("grey PNG with transparency", encode(grey(DataBuffer.TYPE_BYTE, true), "png"), true),
                Arguments.of("16-bit grey PNG with transparency", encode(grey(DataBuffer.TYPE_USHORT, true), "png"),
                        true),
                Arguments.of("grey PNG with a transparent shade", greyPngWithTransparentShade(), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("colourModels")
    void keepsATransparencyChannelOnlyWhenTheUploadHasOne(String label, byte[] upload, boolean transparent) {
        ProcessedAvatar result = processor.process(validator.validate(upload));

        BufferedImage avatar = decode(result.avatar());
        BufferedImage thumbnail = decode(result.thumbnail());
        assertThat(avatar.getWidth()).isEqualTo(256);
        assertThat(thumbnail.getWidth()).isEqualTo(64);
        assertThat(avatar.getColorModel().hasAlpha()).isEqualTo(transparent);
        assertThat(thumbnail.getColorModel().hasAlpha()).isEqualTo(transparent);
    }

    @Test
    void transparentBackgroundStaysTransparentWithoutDarkEdges() {
        // An opaque red disc on a fully transparent background, like a logo. The hidden colour of the transparent
        // pixels is black, so mixing colours without weighting them by opacity would give the disc a dark rim.
        BufferedImage logo = new BufferedImage(1024, 1024, TYPE_INT_ARGB);
        Graphics2D graphics = logo.createGraphics();
        graphics.setColor(RED);
        graphics.fillOval(112, 112, 800, 800);
        graphics.dispose();

        ProcessedAvatar result = processor.process(validator.validate(encode(logo, "png")));

        assertTransparentLogo(decode(result.avatar()));
        assertTransparentLogo(decode(result.thumbnail()));
    }

    @Test
    void transparentPixelOfAPaletteStaysTransparent() {
        BufferedImage avatar = decode(processor.process(
                validator.validate(encode(paletteWithTransparentColour(), "png"))).avatar());

        assertThat(avatar.getRGB(0, 0) >>> 24).isZero();
        assertThat(avatar.getRGB(255, 255)).isEqualTo(RED.getRGB());
    }

    static Stream<Arguments> greyPictures() {
        return Stream.of(
                Arguments.of("grey PNG", encode(grey(DataBuffer.TYPE_BYTE, false), "png"), false),
                Arguments.of("16-bit grey PNG", encode(grey(DataBuffer.TYPE_USHORT, false), "png"), false),
                Arguments.of("grey JPEG", encode(grey(DataBuffer.TYPE_BYTE, false), "jpeg"), false),
                Arguments.of("grey PNG with transparency", encode(grey(DataBuffer.TYPE_BYTE, true), "png"), true),
                Arguments.of("16-bit grey PNG with transparency", encode(grey(DataBuffer.TYPE_USHORT, true), "png"),
                        true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("greyPictures")
    void greyKeepsItsShadeAndTransparency(String label, byte[] upload, boolean transparent) {
        // Java treats grey files as linear light and lightens them when it converts them to RGB (128 becomes 188),
        // which would store grey photos, logos and signatures too light.
        ProcessedAvatar result = processor.process(validator.validate(upload));

        // The top half is opaque mid grey. The bottom half is the same grey, half transparent when the file has
        // transparency.
        int bottom = transparent ? 0x80808080 : 0xFF808080;
        BufferedImage avatar = decode(result.avatar());
        assertThat(avatar.getRGB(128, 64)).isEqualTo(0xFF808080);
        assertThat(avatar.getRGB(128, 192)).isEqualTo(bottom);
        BufferedImage thumbnail = decode(result.thumbnail());
        assertThat(thumbnail.getRGB(32, 16)).isEqualTo(0xFF808080);
        assertThat(thumbnail.getRGB(32, 48)).isEqualTo(bottom);
    }

    static Stream<Arguments> uploadsWithHiddenData() {
        byte[] secret = ascii("GPS 21.0285 105.8542");
        byte[] png = encode(noise(64, 64, TYPE_INT_RGB), "png");
        byte[] jpeg = encode(noise(64, 64, TYPE_INT_RGB), "jpeg");
        // ImageIO starts a JPEG with the start marker (2 bytes) and a JFIF segment; its length counts its own 2 bytes.
        int afterJfif = 4 + (ByteBuffer.wrap(jpeg).getShort(4) & 0xFFFF);
        // A JPEG comment segment: marker FF FE, then the same kind of length.
        byte[] jpegComment = concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
                ByteBuffer.allocate(2).putShort((short) (secret.length + 2)).array(), secret);
        return Stream.of(
                Arguments.of("PNG text chunk", secret,
                        insertAfterPngHeader(png, pngChunk("tEXt", concat(ascii("Comment\0"), secret)))),
                Arguments.of("JPEG comment", secret, concat(Arrays.copyOf(jpeg, afterJfif), jpegComment,
                        Arrays.copyOfRange(jpeg, afterJfif, jpeg.length))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uploadsWithHiddenData")
    void storesOnlyThePixels(String label, byte[] secret, byte[] upload) {
        assertThat(indexOf(upload, secret)).isNotNegative(); // the upload really carries the hidden text

        ProcessedAvatar result = processor.process(validator.validate(upload));

        for (byte[] png : List.of(result.avatar(), result.thumbnail())) {
            // Header, pixel data (the writer may split it into several IDAT chunks) and the end marker; nothing else.
            assertThat(pngChunkTypes(png)).containsOnly("IHDR", "IDAT", "IEND").startsWith("IHDR").endsWith("IEND");
            assertThat(indexOf(png, secret)).isNegative();
        }
    }

    private static void assertQuartersAreKept(BufferedImage image) {
        int last = image.getWidth() - 1;
        int quarter = image.getWidth() / 4;
        assertThat(image.getRGB(0, 0)).isEqualTo(TOP_LEFT.getRGB());
        assertThat(image.getRGB(last, 0)).isEqualTo(TOP_RIGHT.getRGB());
        assertThat(image.getRGB(0, last)).isEqualTo(BOTTOM_LEFT.getRGB());
        assertThat(image.getRGB(last, last)).isEqualTo(BOTTOM_RIGHT.getRGB());
        assertThat(image.getRGB(quarter, quarter)).isEqualTo(TOP_LEFT.getRGB());
        assertThat(image.getRGB(last - quarter, quarter)).isEqualTo(TOP_RIGHT.getRGB());
        assertThat(image.getRGB(quarter, last - quarter)).isEqualTo(BOTTOM_LEFT.getRGB());
        assertThat(image.getRGB(last - quarter, last - quarter)).isEqualTo(BOTTOM_RIGHT.getRGB());
    }

    private static void assertEveryPixelIsTheBlockAverage(BufferedImage large, BufferedImage small, int tolerance) {
        int block = large.getWidth() / small.getWidth();
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int[] sum = new int[3];
                for (int dy = 0; dy < block; dy++) {
                    for (int dx = 0; dx < block; dx++) {
                        Color pixel = new Color(large.getRGB(x * block + dx, y * block + dy));
                        sum[0] += pixel.getRed();
                        sum[1] += pixel.getGreen();
                        sum[2] += pixel.getBlue();
                    }
                }
                Color shrunk = new Color(small.getRGB(x, y));
                double count = block * block;
                assertThat((double) shrunk.getRed()).isCloseTo(sum[0] / count, within(tolerance + 0.5));
                assertThat((double) shrunk.getGreen()).isCloseTo(sum[1] / count, within(tolerance + 0.5));
                assertThat((double) shrunk.getBlue()).isCloseTo(sum[2] / count, within(tolerance + 0.5));
            }
        }
    }

    private static void assertEveryPixelIsGrey(BufferedImage image, int expected) {
        for (int rgb : pixels(image)) {
            Color pixel = new Color(rgb);
            assertThat(pixel.getRed()).isCloseTo(expected, within(1));
            assertThat(pixel.getGreen()).isCloseTo(expected, within(1));
            assertThat(pixel.getBlue()).isCloseTo(expected, within(1));
        }
    }

    private static void assertTransparentLogo(BufferedImage image) {
        int middle = image.getWidth() / 2;
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat(image.getRGB(0, 0) >>> 24).isZero(); // corner outside the disc
        assertThat(image.getRGB(middle, middle)).isEqualTo(RED.getRGB()); // fully opaque red
        int partlyTransparent = 0;
        for (int rgb : pixels(image)) {
            int alpha = rgb >>> 24;
            if (alpha > 0 && alpha < 255) {
                partlyTransparent++;
                // Compare colour x opacity, which is what shows on screen. A nearly invisible pixel can only keep a
                // rough colour, while a dark rim (half-transparent black mixed in) would be off by about 50 here.
                Color pixel = new Color(rgb);
                double opacity = alpha / 255.0;
                assertThat(pixel.getRed() * opacity).isCloseTo(RED.getRed() * opacity, within(1.5));
                assertThat(pixel.getGreen() * opacity).isCloseTo(RED.getGreen() * opacity, within(1.5));
                assertThat(pixel.getBlue() * opacity).isCloseTo(RED.getBlue() * opacity, within(1.5));
            }
        }
        assertThat(partlyTransparent).isPositive(); // the soft edge of the disc exists and was checked
    }

    private static BufferedImage bands(int width, int height, int squareLeft, int squareTop) {
        BufferedImage image = new BufferedImage(width, height, TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int squareX = x - squareLeft;
                int squareY = y - squareTop;
                Color colour;
                if (squareX < 0 || squareY < 0) {
                    colour = RED;
                } else if (squareX >= 200 || squareY >= 200) {
                    colour = BLUE;
                } else if (squareY < 100) {
                    colour = squareX < 100 ? TOP_LEFT : TOP_RIGHT;
                } else {
                    colour = squareX < 100 ? BOTTOM_LEFT : BOTTOM_RIGHT;
                }
                image.setRGB(x, y, colour.getRGB());
            }
        }
        return image;
    }

    private static BufferedImage noise(int width, int height, int imageType) {
        return withNoise(new BufferedImage(width, height, imageType));
    }

    // 16 bits per channel with transparency: ImageIO writes it as a 16-bit RGBA PNG.
    private static BufferedImage noise16BitWithAlpha(int width, int height) {
        var colourModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), true, false,
                Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT);
        return withNoise(new BufferedImage(colourModel, colourModel.createCompatibleWritableRaster(width, height),
                false, null));
    }

    private static BufferedImage withNoise(BufferedImage image) {
        Random random = new Random(42);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }

    // Two colours: index 0 is fully transparent, index 1 is red. The upper-left triangle is transparent.
    private static BufferedImage paletteWithTransparentColour() {
        byte[] reds = {0, (byte) RED.getRed()};
        byte[] greens = {0, (byte) RED.getGreen()};
        byte[] blues = {0, (byte) RED.getBlue()};
        var palette = new IndexColorModel(8, 2, reds, greens, blues, 0);
        BufferedImage image = new BufferedImage(80, 80, TYPE_BYTE_INDEXED, palette);
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 80; x++) {
                image.getRaster().setSample(x, y, 0, x + y < 80 ? 0 : 1);
            }
        }
        return image;
    }

    // Mid grey stored the way grey PNG files store it: one grey sample per pixel (128 of 255, or 0x8080 of 0xFFFF),
    // plus an alpha sample when transparent. With transparency the bottom half is half transparent.
    private static BufferedImage grey(int dataType, boolean transparent) {
        var colourModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), transparent, false,
                transparent ? Transparency.TRANSLUCENT : Transparency.OPAQUE, dataType);
        WritableRaster raster = colourModel.createCompatibleWritableRaster(40, 40);
        int opaque = dataType == DataBuffer.TYPE_USHORT ? 0xFFFF : 0xFF;
        int middle = dataType == DataBuffer.TYPE_USHORT ? 0x8080 : 0x80;
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                raster.setSample(x, y, 0, middle);
                if (transparent) {
                    raster.setSample(x, y, 1, y < 20 ? opaque : middle);
                }
            }
        }
        return new BufferedImage(colourModel, raster, false, null);
    }

    // A grey PNG without an alpha channel whose tRNS chunk marks the shade 128 (every pixel) as transparent.
    private static byte[] greyPngWithTransparentShade() {
        byte[] png = encode(grey(DataBuffer.TYPE_BYTE, false), "png");
        // For a grey PNG the chunk holds the transparent shade as a 2-byte number.
        return insertAfterPngHeader(png, pngChunk("tRNS", new byte[] {0, (byte) 0x80}));
    }

    private static byte[] insertAfterPngHeader(byte[] png, byte[] chunk) {
        int afterHeader = 8 + 25; // 8-byte signature + IHDR chunk (length, type, 13 data bytes, CRC)
        return concat(Arrays.copyOf(png, afterHeader), chunk, Arrays.copyOfRange(png, afterHeader, png.length));
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

    private static BufferedImage decode(byte[] content) {
        try {
            return ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // Lists the chunk types of a PNG file in order; each chunk is length, type, data and CRC.
    private static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(png);
        for (int offset = 8; offset < png.length; offset += 12 + buffer.getInt(offset)) {
            types.add(new String(png, offset + 4, 4, StandardCharsets.US_ASCII));
        }
        return types;
    }

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] typeBytes = ascii(type);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data)
                .putInt((int) crc.getValue()).array();
    }

    private static int indexOf(byte[] content, byte[] part) {
        for (int start = 0; start + part.length <= content.length; start++) {
            if (Arrays.equals(content, start, start + part.length, part, 0, part.length)) {
                return start;
            }
        }
        return -1;
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
