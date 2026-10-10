package vn.ttcs.recruitment.account.avatar;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the bytes of an uploaded avatar: JPG or PNG only, at most 2MB, at most 4096 pixels per side and fully
 * decodable. The checks run from the cheapest to the most expensive:
 * empty file, file size, magic bytes, width/height from the header, then a full decode that keeps a large photo at a
 * reduced size.
 */
@Component
public class AvatarImageValidator {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    // Even at a reduced size (see MAX_DECODED_SIDE) the decoder reads every pixel of the file, so this caps the work.
    public static final int MAX_DIMENSION = 4096;
    // An avatar is shown at 256 x 256 at most. A bigger photo is decoded keeping only every 2nd, 3rd or 4th pixel,
    // so the decoded image needs at most 8MB. At full size a 4096 x 4096 PNG with 16-bit colour needs 128MB, although
    // a single flat colour compresses that file to about 200KB.
    public static final int MAX_DECODED_SIDE = 1024;

    public ValidatedAvatarImage validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw invalid("Tệp ảnh đại diện đang trống.");
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "AVATAR_TOO_LARGE",
                    "Ảnh đại diện có dung lượng tối đa 2MB.");
        }
        AvatarImageFormat format = AvatarImageFormat.detect(content).orElseThrow(() -> new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AVATAR_TYPE_UNSUPPORTED",
                "Ảnh đại diện chỉ chấp nhận định dạng JPG hoặc PNG."));
        return new ValidatedAvatarImage(format, decode(content, format));
    }

    private BufferedImage decode(byte[] content, AvatarImageFormat format) {
        // Only the reader of the detected format is used, so bytes starting like a PNG are never decoded as GIF etc.
        ImageReader reader = ImageIO.getImageReadersByFormatName(format.readerName()).next();
        // A cut-off or damaged JPEG does not throw: the reader reports a warning and fills the gap with gray.
        List<String> warnings = new ArrayList<>();
        reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
        BufferedImage image;
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            reader.setInput(input, true, true); // read forward only and skip metadata (EXIF, GPS, comments)
            // getWidth/getHeight read only the header, so a huge image is refused before its pixels use memory.
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_DIMENSIONS_TOO_LARGE",
                        "Ảnh đại diện có kích thước tối đa 4096 x 4096 điểm ảnh.");
            }
            // step 1 keeps every pixel; step n keeps every n-th pixel in both directions. Skipped pixels are still
            // read and checked by the decoder, they are only not stored in the result.
            int step = Math.ceilDiv(Math.max(width, height), MAX_DECODED_SIDE);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(step, step, 0, 0);
            image = reader.read(0, param);
        } catch (ApiException exception) {
            throw exception; // keep AVATAR_DIMENSIONS_TOO_LARGE instead of turning it into AVATAR_INVALID below
        } catch (IOException | RuntimeException exception) {
            // Decoders can also throw unchecked exceptions on crafted bytes; that is a bad upload, not a server error.
            throw damaged();
        } finally {
            reader.dispose();
        }
        if (!warnings.isEmpty()) {
            throw damaged();
        }
        return image;
    }

    private static ApiException damaged() {
        return invalid("Tệp ảnh đại diện bị hỏng hoặc không đầy đủ.");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_INVALID", message);
    }
}
