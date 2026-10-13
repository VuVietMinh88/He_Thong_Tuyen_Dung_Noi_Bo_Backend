package vn.ttcs.recruitment.companyprofile;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyImageInspectorTest {

    @Test
    void readsTheTypeAndPixelSizeOfPngAndJpegPictures() {
        assertInspected(TestImages.png(64, 48), "image/png", 64, 48);
        assertInspected(TestImages.pngWithTransparency(30, 20), "image/png", 30, 20);
        assertInspected(TestImages.grayPng(10, 12), "image/png", 10, 12);
        assertInspected(TestImages.jpeg(64, 48), "image/jpeg", 64, 48);
        assertInspected(TestImages.jpeg(1, 1), "image/jpeg", 1, 1);
    }

    @Test
    void acceptsTheLargestAllowedPixelSizeAndFileSize() {
        assertInspected(TestImages.png(CompanyMedia.MAX_DIMENSION, 1), "image/png", 6000, 1);
        assertInspected(TestImages.jpeg(1, CompanyMedia.MAX_DIMENSION), "image/jpeg", 1, 6000);
        byte[] fiveMegabytes = TestImages.pngOfExactSize(40, 30, CompanyMedia.MAX_SIZE_BYTES);
        assertThat(fiveMegabytes).hasSize(5 * 1024 * 1024);
        assertInspected(fiveMegabytes, "image/png", 40, 30);
    }

    @Test
    void refusesOtherFormatsWhateverTheFileIsCalled() {
        Map<String, byte[]> others = new LinkedHashMap<>();
        others.put("GIF", TestImages.gif(10, 10));
        others.put("BMP", TestImages.bmp(10, 10));
        others.put("SVG with a script", text("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"));
        others.put("HTML", text("<html><body><script>alert(1)</script></body></html>"));
        others.put("WebP header", text("RIFF\0\0\0\0WEBPVP8 "));
        others.put("first half of the PNG signature", Arrays.copyOf(TestImages.png(10, 10), 4));
        others.put("empty file", new byte[0]);
        others.forEach((name, data) ->
                assertRefused(data, HttpStatus.BAD_REQUEST, "UNSUPPORTED_COMPANY_MEDIA_TYPE", name));
    }

    @Test
    void refusesCutOrDamagedPicturesEvenWithACorrectSignature() {
        byte[] png = TestImages.png(64, 48);
        byte[] jpeg = TestImages.jpeg(64, 48);
        byte[] damagedJpeg = jpeg.clone();
        for (int index = damagedJpeg.length / 2; index < damagedJpeg.length - 2; index++) {
            damagedJpeg[index] = (byte) (index * 7);
        }
        Map<String, byte[]> broken = new LinkedHashMap<>();
        broken.put("PNG cut in half", Arrays.copyOf(png, png.length / 2));
        // ImageIO only warns about this one and paints the missing part grey: the warning check refuses it.
        broken.put("JPEG cut in its picture data", TestImages.jpegCutInPictureData(64, 48));
        broken.put("JPEG cut in its headers", Arrays.copyOf(jpeg, 200));
        broken.put("JPEG with damaged picture data", damagedJpeg);
        broken.put("PNG signature followed by HTML", concat(Arrays.copyOf(png, 8), text("<html><script>alert(1)</script>")));
        broken.put("JPEG signature followed by HTML", concat(HexFormat.of().parseHex("ffd8ff"), text("<html>alert(1)</html>")));
        broken.put("PNG signature only", Arrays.copyOf(png, 8));
        broken.put("PNG header claiming 0 pixels wide", TestImages.pngClaimingSize(0, 10));
        broken.forEach((name, data) -> assertRefused(data, HttpStatus.BAD_REQUEST, "INVALID_COMPANY_MEDIA", name));
    }

    @Test
    void refusesHugePixelSizesBeforeDecoding() {
        // Only the header is real: decoding would fail with INVALID_COMPANY_MEDIA, so this code proves
        // that the pixel size is checked before any pixel is decoded.
        byte[] bomb = TestImages.pngClaimingSize(100_000, 100_000);
        assertThat(bomb.length).isLessThan(1_000);
        assertRefused(bomb, HttpStatus.BAD_REQUEST, "COMPANY_MEDIA_DIMENSIONS_TOO_LARGE", "decompression bomb");
        assertRefused(TestImages.png(6001, 1), HttpStatus.BAD_REQUEST, "COMPANY_MEDIA_DIMENSIONS_TOO_LARGE", "6001 wide");
        assertRefused(TestImages.jpeg(1, 6001), HttpStatus.BAD_REQUEST, "COMPANY_MEDIA_DIMENSIONS_TOO_LARGE", "6001 high");
    }

    @Test
    void refusesFilesAboveFiveMegabytes() {
        byte[] tooLarge = TestImages.pngOfExactSize(40, 30, CompanyMedia.MAX_SIZE_BYTES + 1);
        assertRefused(tooLarge, HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "5 MB + 1 byte");
    }

    private static void assertInspected(byte[] data, String contentType, int width, int height) {
        assertThat(CompanyImageInspector.inspect(data))
                .isEqualTo(new CompanyImageInspector.InspectedImage(contentType, width, height));
    }

    private static void assertRefused(byte[] data, HttpStatus status, String code, String description) {
        assertThatThrownBy(() -> CompanyImageInspector.inspect(data)).as(description)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).as(description).isEqualTo(status);
                    assertThat(exception.getCode()).as(description).isEqualTo(code);
                });
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
