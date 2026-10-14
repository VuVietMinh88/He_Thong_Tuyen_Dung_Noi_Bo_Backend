package vn.ttcs.recruitment.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ttcs.recruitment.common.ApiException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CvValidatorTest {
    private final CvValidator validator = new CvValidator();

    @Test
    void keepsPdfBytesAndStripsPathsWithoutTrustingClientMime() throws Exception {
        byte[] pdf = pdf(false);
        ValidatedCv cv = validator.validate("C:\\fakepath\\CV.PDF", new ByteArrayInputStream(pdf));
        assertThat(cv.filename()).isEqualTo("CV.PDF");
        assertThat(cv.contentType()).isEqualTo("application/pdf");
        assertThat(cv.content()).isEqualTo(pdf);
        assertThat(cv.sha256()).hasSize(32);
        byte[] exposed = cv.content();
        exposed[0] = 0;
        assertThat(cv.content()).isEqualTo(pdf);
        assertThat(cv.toString()).doesNotContain("CV.PDF");
    }

    @Test
    void acceptsWordPackageAndPreservesTheOriginalBytes() throws Exception {
        byte[] docx = docx();
        ValidatedCv cv = validator.validate("../cv.docx", new ByteArrayInputStream(docx));
        assertThat(cv.filename()).isEqualTo("cv.docx");
        assertThat(cv.contentType()).contains("wordprocessingml.document");
        assertThat(cv.content()).isEqualTo(docx);
    }

    @Test
    void exactlyTenMebibytesIsAcceptedButOneMoreByteIsNot() throws Exception {
        // Put padding inside a real PDF stream; padding after EOF is not a valid strict PDF boundary fixture.
        int payloadSize = CvValidator.MAX_BYTES - 1000;
        byte[] boundary = pdfWithPayload(payloadSize);
        for (int attempt = 0; boundary.length != CvValidator.MAX_BYTES && attempt < 5; attempt++) {
            payloadSize += CvValidator.MAX_BYTES - boundary.length;
            boundary = pdfWithPayload(payloadSize);
        }
        assertThat(boundary).hasSize(CvValidator.MAX_BYTES);
        assertThat(validator.validate("cv.pdf", new ByteArrayInputStream(boundary)).sizeBytes()).isEqualTo(CvValidator.MAX_BYTES);
        assertCode("cv.pdf", Arrays.copyOf(boundary, boundary.length + 1), "APPLICATION_CV_TOO_LARGE");
    }

    @Test
    void neverReadsAnUnboundedOrUnreliableStream() {
        var infinite = new InputStream() {
            long read;
            @Override public int read() {
                if (++read > CvValidator.MAX_BYTES + 1L) throw new AssertionError("read beyond the limit");
                return 0;
            }
        };
        assertThatThrownBy(() -> validator.validate("cv.pdf", infinite)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("APPLICATION_CV_TOO_LARGE"));
    }

    @Test
    void refusesEmptyTruncatedEncryptedAndSpoofedFiles() throws Exception {
        assertCode("cv.pdf", new byte[0], "APPLICATION_CV_INVALID");
        assertCode("cv.pdf", "%PDF-1.7\nnot a PDF".getBytes(StandardCharsets.US_ASCII), "APPLICATION_CV_INVALID");
        assertCode("cv.pdf", pdf(true), "APPLICATION_CV_INVALID");
        assertCode("cv.docx", pdf(false), "APPLICATION_CV_TYPE_UNSUPPORTED");
        assertCode("cv.exe", pdf(false), "APPLICATION_CV_TYPE_UNSUPPORTED");
        assertCode("cv.docx", zip("other.txt", "hello".getBytes(StandardCharsets.UTF_8)), "APPLICATION_CV_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "cv\u0000.pdf", "cv\u202e.pdf", "cv.pdf\n"})
    void refusesUnsafeDisplayNames(String name) throws Exception {
        assertCode(name, pdf(false), "APPLICATION_CV_INVALID");
    }

    @Test
    void refusesMacroTraversalExternalEntitiesAndHighlyCompressedEntries() throws Exception {
        assertCode("cv.docx", zip("word/vbaProject.bin", new byte[]{1}), "APPLICATION_CV_INVALID");
        assertCode("cv.docx", zip("../word/document.xml", new byte[]{1}), "APPLICATION_CV_INVALID");
        assertCode("cv.docx", zip("word/document.xml", ("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///never-read'>]>"
                + "<x>&e;</x>").getBytes(StandardCharsets.UTF_8)), "APPLICATION_CV_INVALID");
        assertCode("cv.docx", zip("word/media/bomb.bin", new byte[2 * 1024 * 1024]), "APPLICATION_CV_INVALID");
        assertCode("cv.docx", zip("word/document.xml", ("<a>".repeat(129) + "</a>".repeat(129))
                .getBytes(StandardCharsets.UTF_8)), "APPLICATION_CV_INVALID");
    }

    private void assertCode(String filename, byte[] bytes, String code) {
        assertThatThrownBy(() -> validator.validate(filename, new ByteArrayInputStream(bytes)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo(code));
    }

    static byte[] pdf(boolean encrypted) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            if (encrypted) document.protect(new StandardProtectionPolicy("owner", "secret", new AccessPermission()));
            document.save(output);
            return output.toByteArray();
        }
    }

    static byte[] docx() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Candidate CV");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdfWithPayload(int size) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            var contents = new PDStream(document);
            page.setContents(contents);
            try (var stream = contents.createOutputStream()) {
                byte[] spaces = new byte[8192]; Arrays.fill(spaces, (byte) ' ');
                for (int left = size; left > 0; left -= Math.min(left, spaces.length)) stream.write(spaces, 0, Math.min(left, spaces.length));
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] zip(String name, byte[] bytes) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
