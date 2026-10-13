package vn.ttcs.recruitment.companyprofile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

// Small pictures generated with ImageIO for the upload tests, so no binary file is kept in the repository.
public final class TestImages {

    private TestImages() {
    }

    public static byte[] png(int width, int height) {
        return encode("png", width, height, BufferedImage.TYPE_INT_RGB);
    }

    public static byte[] pngWithTransparency(int width, int height) {
        return encode("png", width, height, BufferedImage.TYPE_INT_ARGB);
    }

    public static byte[] grayPng(int width, int height) {
        return encode("png", width, height, BufferedImage.TYPE_BYTE_GRAY);
    }

    public static byte[] jpeg(int width, int height) {
        return encode("jpeg", width, height, BufferedImage.TYPE_INT_RGB);
    }

    // A JPEG whose headers are complete but whose compressed pixels stop halfway, like an interrupted download.
    public static byte[] jpegCutInPictureData(int width, int height) {
        byte[] picture = jpeg(width, height);
        // The compressed pixels follow the start-of-scan marker FF DA.
        int pixels = 2;
        while (!(picture[pixels - 2] == (byte) 0xFF && picture[pixels - 1] == (byte) 0xDA)) {
            pixels++;
        }
        return Arrays.copyOf(picture, pixels + (picture.length - pixels) / 2);
    }

    public static byte[] gif(int width, int height) {
        return encode("gif", width, height, BufferedImage.TYPE_INT_RGB);
    }

    public static byte[] bmp(int width, int height) {
        return encode("bmp", width, height, BufferedImage.TYPE_INT_RGB);
    }

    // A valid PNG of exactly totalBytes bytes: a text chunk ("tEXt", allowed by the PNG standard)
    // placed after the header fills the file up to that size.
    public static byte[] pngOfExactSize(int width, int height, int totalBytes) {
        byte[] picture = png(width, height);
        int fillerLength = totalBytes - picture.length - 12;
        byte[] text = new byte[fillerLength];
        Arrays.fill(text, (byte) 'x');
        byte[] keyword = "Comment\0".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(keyword, 0, text, 0, keyword.length);
        byte[] chunk = chunk("tEXt", text);
        // Signature (8 bytes) and header chunk IHDR (25 bytes) come first.
        int afterHeader = 33;
        ByteBuffer result = ByteBuffer.allocate(totalBytes);
        result.put(picture, 0, afterHeader).put(chunk).put(picture, afterHeader, picture.length - afterHeader);
        return result.array();
    }

    // A real small PNG whose header claims width x height pixels: a "decompression bomb" that is tiny on disk.
    public static byte[] pngClaimingSize(int width, int height) {
        byte[] picture = png(8, 8);
        byte[] header = Arrays.copyOfRange(picture, 16, 29);
        ByteBuffer.wrap(header).putInt(0, width).putInt(4, height);
        byte[] patched = chunk("IHDR", header);
        System.arraycopy(patched, 0, picture, 8, patched.length);
        return picture;
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data)
                .putInt((int) crc.getValue()).array();
    }

    private static byte[] encode(String format, int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | (x * 7 % 256) << 16 | (y * 5 % 256) << 8 | (x + y) % 256);
            }
        }
        var output = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return output.toByteArray();
    }
}
