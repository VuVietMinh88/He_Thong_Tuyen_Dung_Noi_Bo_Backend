package vn.ttcs.recruitment.candidate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ttcs.recruitment.candidate.CvContentDisposition.StoredType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 362 (story S4-09): the Content-Disposition header of GET /api/v1/applications/{id}/cv. Vietnamese letters are
 * built from code points: the tools that write these files turn escape sequences into raw characters.
 */
class CvContentDispositionTest {
    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    // The frontend's pattern (candidateProfileService, task 359): it takes the first match.
    private static final Pattern FRONTEND = Pattern.compile("filename=\"?([^\";]+)\"?");
    // "CV Nguyễn Văn Đức.pdf": ễ U+1EC5, ă U+0103, Đ U+0110, ứ U+1EE9.
    private static final String DUC = "CV Nguy" + cp(0x1EC5) + "n V" + cp(0x0103) + "n " + cp(0x0110) + cp(0x1EE9)
            + "c.pdf";

    @Test
    void aVietnameseNameGetsAnAsciiFallbackFirstAndTheExactNameInFilenameStar() {
        String header = CvContentDisposition.header(DUC, PDF);

        assertThat(header).isEqualTo("inline; filename=\"CV Nguyen Van Duc.pdf\"; filename*=UTF-8''"
                + "CV%20Nguy%E1%BB%85n%20V%C4%83n%20%C4%90%E1%BB%A9c.pdf");
        assertThat(decode(header.substring(header.indexOf("UTF-8''") + "UTF-8''".length()))).isEqualTo(DUC);
        assertThat(header.chars().allMatch(c -> c >= 0x20 && c < 0x7F)).as("ASCII only").isTrue();
    }

    // A decomposed name (NFD, as some systems store it) gives the same ASCII name; đ becomes d.
    @Test
    void decomposedLettersAndTheLetterDBecomeAscii() {
        String decomposed = Normalizer.normalize(DUC, Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo(DUC);
        assertThat(CvContentDisposition.asciiName(decomposed, StoredType.PDF)).isEqualTo("CV Nguyen Van Duc.pdf");
        assertThat(CvContentDisposition.asciiName(cp(0x0111) + "on " + cp(0x0111) + "en.pdf", StoredType.PDF))
                .isEqualTo("don den.pdf");
    }

    // Quotes, semicolons, backslashes, line breaks and other separators never reach the header as such: they become
    // "_" (one per run) in the ASCII name and %XX in filename*, so the header keeps its two parameters.
    @Test
    void separatorsAndLineBreaksCannotInjectIntoTheHeader() {
        String hostile = "a\"b;c\\d\r\ne" + cp(0x2028) + "f,g=h.pdf";
        String header = CvContentDisposition.header(hostile, PDF);

        assertThat(CvContentDisposition.asciiName(hostile, StoredType.PDF)).isEqualTo("a_b_c_d_e_f_g_h.pdf");
        assertThat(header).doesNotContain("\r", "\n", "\\");
        assertThat(header.chars().filter(c -> c == '"').count()).isEqualTo(2);
        assertThat(header.chars().filter(c -> c == ';').count()).isEqualTo(2);
        assertThat(header).endsWith("filename*=UTF-8''a%22b%3Bc%5Cd%0D%0Ae%E2%80%A8f%2Cg%3Dh.pdf");
        assertThat(frontendName(header)).isEqualTo("a_b_c_d_e_f_g_h.pdf");
    }

    // Nothing usable left: cv.pdf or cv.docx, in both parameters when the stored name has no visible character.
    @ParameterizedTest
    @ValueSource(strings = {"!!!@@@.pdf", "...pdf", "___", "   .pdf", "%%%"})
    void aNameOfSymbolsOnlyBecomesCv(String stored) {
        assertThat(CvContentDisposition.asciiName(stored, StoredType.PDF)).isEqualTo("cv.pdf");
        assertThat(CvContentDisposition.asciiName(stored, StoredType.DOCX)).isEqualTo("cv.docx");
    }

    @Test
    void aNameWithoutAVisibleCharacterIsCvInBothParameters() {
        for (String stored : new String[] {"", " ", cp(0x200B) + ".pdf", null}) {
            assertThat(CvContentDisposition.header(stored, PDF))
                    .isEqualTo("inline; filename=\"cv.pdf\"; filename*=UTF-8''cv.pdf");
        }
    }

    // The stored type decides the extension: a missing, wrong or upper-case one is replaced or added.
    @Test
    void theExtensionAlwaysFollowsTheStoredType() {
        assertThat(CvContentDisposition.header("CV", PDF)).isEqualTo("inline; filename=\"CV.pdf\"; filename*=UTF-8''CV.pdf");
        assertThat(CvContentDisposition.header("CV.docx", PDF)).isEqualTo("inline; filename=\"CV.pdf\"; filename*=UTF-8''CV.pdf");
        assertThat(CvContentDisposition.header("CV.PDF", PDF)).isEqualTo("inline; filename=\"CV.pdf\"; filename*=UTF-8''CV.pdf");
        assertThat(CvContentDisposition.header("CV.pdf", DOCX))
                .isEqualTo("attachment; filename=\"CV.docx\"; filename*=UTF-8''CV.docx");
        assertThat(CvContentDisposition.header("CV.exe", DOCX))
                .isEqualTo("attachment; filename=\"CV.exe.docx\"; filename*=UTF-8''CV.exe.docx");
        assertThat(CvContentDisposition.header(".hidden.pdf", PDF)).startsWith("inline; filename=\"hidden.pdf\";");
    }

    // At most 150 characters, the extension kept, nothing dangling at the cut.
    @Test
    void aLongNameIsCutToOneHundredFiftyCharactersKeepingTheExtension() {
        String ascii = CvContentDisposition.asciiName("a".repeat(300) + ".docx", StoredType.DOCX);
        assertThat(ascii).hasSize(CvContentDisposition.MAX_ASCII_LENGTH).endsWith("a.docx")
                .isEqualTo("a".repeat(145) + ".docx");

        String cutAtSpace = CvContentDisposition.asciiName("a".repeat(145) + " b".repeat(50) + ".pdf", StoredType.PDF);
        assertThat(cutAtSpace).isEqualTo("a".repeat(145) + ".pdf");

        String header = CvContentDisposition.header(DUC.replace(".pdf", "") + " " + "x".repeat(300) + ".pdf", PDF);
        assertThat(frontendName(header)).hasSizeLessThanOrEqualTo(150).endsWith(".pdf").startsWith("CV Nguyen Van Duc x");
    }

    // The frontend's pattern takes the quoted ASCII name, never the RFC 5987 value.
    @Test
    void theFrontendPatternReadsTheAsciiName() {
        assertThat(frontendName(CvContentDisposition.header(DUC, PDF))).isEqualTo("CV Nguyen Van Duc.pdf");
        assertThat(frontendName(CvContentDisposition.header("CV (1) - final_v2.docx", DOCX)))
                .isEqualTo("CV (1) - final_v2.docx");
    }

    // Every stored type has its disposition and extension (exhaustive switches); a PDF is inline, a DOCX never is.
    @ParameterizedTest
    @EnumSource(StoredType.class)
    void everyStoredTypeHasADispositionAndAnExtension(StoredType type) {
        assertThat(StoredType.of(type.contentType())).isEqualTo(type);
        String header = CvContentDisposition.header("cv", type.contentType());
        switch (type) {
            case PDF -> assertThat(header).startsWith("inline; filename=\"cv.pdf\"");
            case DOCX -> assertThat(header).startsWith("attachment; filename=\"cv.docx\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/octet-stream", "application/PDF", ""})
    void anUnknownStoredTypeFailsLoudly(String type) {
        assertThatThrownBy(() -> CvContentDisposition.header("cv.pdf", type)).isInstanceOf(IllegalStateException.class);
    }

    // RFC 5987 attr-chars stay as they are; everything else, space included, is percent-encoded in upper case.
    @Test
    void filenameStarKeepsOnlyAttrChars() {
        assertThat(CvContentDisposition.encode("Az09!#$&+-.^_`|~")).isEqualTo("Az09!#$&+-.^_`|~");
        assertThat(CvContentDisposition.encode(" ()'*%/:<>?@[]{}")).isEqualTo(
                "%20%28%29%27%2A%25%2F%3A%3C%3E%3F%40%5B%5D%7B%7D");
        assertThat(CvContentDisposition.encode(cp(0x1F600))).isEqualTo("%F0%9F%98%80");
    }

    private static String frontendName(String header) {
        Matcher match = FRONTEND.matcher(header);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private static String decode(String value) {
        var bytes = new ByteArrayOutputStream();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%') {
                bytes.write(Integer.parseInt(value.substring(index + 1, index + 3), 16));
                index += 2;
            } else {
                bytes.write(character);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String cp(int codePoint) {
        return Character.toString(codePoint);
    }
}
