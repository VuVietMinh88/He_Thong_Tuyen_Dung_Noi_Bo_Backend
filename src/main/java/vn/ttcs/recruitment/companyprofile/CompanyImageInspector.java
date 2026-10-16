package vn.ttcs.recruitment.companyprofile;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Proves that uploaded bytes are a real JPEG or PNG picture and reads its pixel size.
// The type is read from the first bytes of the file, never from the file name or from the Content-Type
// the browser sent, so a GIF, an SVG or a text/HTML file renamed to ".png" is refused.
final class CompanyImageInspector {
    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";

    // Same first bytes as the V11 CHECK valid_company_media_signature.
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    // While decoding, at most about 1000 x 1000 pixels are kept in memory (see decodeEveryRow).
    private static final int DECODED_SIDE = 1000;

    record InspectedImage(String contentType, int width, int height) { }

    private CompanyImageInspector() {
    }

    static InspectedImage inspect(byte[] data) {
        if (data.length > CompanyMedia.MAX_SIZE_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Ảnh tải lên tối đa 5 MB.");
        }
        String contentType = contentType(data);
        ImageReader reader = ImageIO.getImageReadersByMIMEType(contentType).next();
        // The JPEG decoder reports a cut or damaged file only as a warning and paints the missing part grey.
        List<String> warnings = new ArrayList<>();
        reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input, true, true);
            // The header gives the size without decoding any pixel. A small file can claim a huge picture
            // (a "decompression bomb"), so the size is checked before decoding.
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1) {
                throw damaged();
            }
            if (width > CompanyMedia.MAX_DIMENSION || height > CompanyMedia.MAX_DIMENSION) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_MEDIA_DIMENSIONS_TOO_LARGE",
                        "Ảnh tối đa 6000 x 6000 pixel.");
            }
            decodeEveryRow(reader, width, height);
            if (!warnings.isEmpty()) {
                throw damaged();
            }
            return new InspectedImage(contentType, width, height);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // ImageIO reports broken files with IIOException, but also with exceptions such as
            // IllegalArgumentException or IndexOutOfBoundsException.
            throw damaged();
        } finally {
            reader.dispose();
        }
    }

    private static String contentType(byte[] data) {
        if (startsWith(data, PNG_SIGNATURE)) {
            return PNG;
        }
        if (startsWith(data, JPEG_SIGNATURE)) {
            return JPEG;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_COMPANY_MEDIA_TYPE",
                "Chỉ nhận ảnh định dạng JPG hoặc PNG.");
    }

    // Decodes every pixel row, so a cut or damaged file fails here. Only every step-th pixel is kept:
    // a 6000 x 6000 picture kept whole would need more than 100 MB of memory.
    private static void decodeEveryRow(ImageReader reader, int width, int height) throws IOException {
        int step = (Math.max(width, height) + DECODED_SIDE - 1) / DECODED_SIDE;
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(step, step, 0, 0);
        reader.read(0, param);
    }

    private static boolean startsWith(byte[] data, byte[] signature) {
        return data.length >= signature.length
                && Arrays.equals(data, 0, signature.length, signature, 0, signature.length);
    }

    private static ApiException damaged() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPANY_MEDIA",
                "Tệp ảnh bị hỏng hoặc không đầy đủ.");
    }
}
