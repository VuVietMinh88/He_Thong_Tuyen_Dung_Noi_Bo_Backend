package vn.ttcs.recruitment.companyprofile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.UUID;

// Body of PUT /company-profile and POST /company-profile/preview. Both use the same rules, so a preview
// that succeeds can always be saved. Limits match the V11 CHECK constraints of CompanyProfile.
public record CompanyProfileRequest(
        @NotBlank(message = "Tên công ty không được để trống.")
        @Size(max = 255, message = "Tên công ty tối đa 255 ký tự.")
        @PlainText(message = "Tên công ty phải là văn bản thuần trên một dòng, có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String companyName,
        @Size(max = 255, message = "Khẩu hiệu tối đa 255 ký tự.")
        @PlainText(message = "Khẩu hiệu phải là văn bản thuần trên một dòng, có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String tagline,
        @NotBlank(message = "Nội dung giới thiệu không được để trống.")
        @Size(max = CompanyProfile.MAX_INTRODUCTION_LENGTH, message = "Nội dung giới thiệu tối đa 20.000 ký tự.")
        @PlainText(multiline = true,
                message = "Nội dung giới thiệu phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String introduction,
        UUID logoMediaId,
        // V11 checks a repeated image only at commit, too late for a clear error, so it is refused here.
        @Size(max = CompanyProfile.MAX_IMAGES, message = "Chỉ được chọn tối đa 10 ảnh giới thiệu.")
        @UniqueElements(message = "Mỗi ảnh giới thiệu chỉ được chọn một lần.")
        List<@NotNull(message = "Mã ảnh giới thiệu không được để trống.") UUID> imageIds) {

    public CompanyProfileRequest {
        companyName = stripSpaces(companyName);
        String strippedTagline = stripSpaces(tagline);
        tagline = strippedTagline == null || strippedTagline.isEmpty() ? null : strippedTagline;
        // Windows (\r\n) and old Mac (\r) line breaks become \n, so preview, saved text and length agree.
        introduction = introduction == null ? null
                : stripSpaces(introduction.replace("\r\n", "\n").replace('\r', '\n'));
        imageIds = imageIds == null ? List.of() : imageIds;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported company profile field");
    }

    // Removes every kind of space at both ends, including non-breaking spaces, which trim() keeps.
    // V11 refuses names that start or end with a space. Line breaks inside the text are kept.
    private static String stripSpaces(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
