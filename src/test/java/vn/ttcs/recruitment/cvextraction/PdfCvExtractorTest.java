package vn.ttcs.recruitment.cvextraction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.cvextraction.CvExtractionResult.*;

class PdfCvExtractorTest {
    static final String CONTACT = "Full name: Alice Nguyen\nEmail: alice@example.test\nPhone: 0912345678";
    static byte[] pdf(String... pages) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (String text : pages) {
                var page = new PDPage(); document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.setLeading(16); content.newLineAtOffset(50, 740);
                    for (String line : text.split("\n")) { content.showText(line); content.newLine(); }
                    content.endText();
                }
            }
            document.save(output); return output.toByteArray();
        }
    }
    @Test void textPdfDoesNotNeedOcrWhenCoverageIsEnough() throws Exception {
        var calls = new AtomicInteger();
        var result = new PdfCvExtractor(image -> { calls.incrementAndGet(); return ""; }).extract(pdf(CONTACT + "\nExperience with Java and SQL applications"));
        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETE);
        assertThat(calls).hasValue(0);
        assertThat(result.suggestions().fullName().getFirst().source()).isEqualTo(Source.TEXT);
    }
    @Test void sparsePageUsesOcrAndDeduplicatesExistingText() throws Exception {
        var result = new PdfCvExtractor(image -> CONTACT).extract(pdf("Email: alice@example.test"));
        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETE);
        assertThat(result.suggestions().email()).hasSize(1);
        assertThat(result.suggestions().phone().getFirst().source()).isEqualTo(Source.OCR);
    }
    @Test void mixedDocumentProcessesEachPageIndependently() throws Exception {
        var calls = new AtomicInteger();
        var result = new PdfCvExtractor(image -> { calls.incrementAndGet(); return CONTACT; })
                .extract(pdf("Education and professional experience with distributed Java applications and databases".repeat(2), ""));
        assertThat(calls).hasValue(1); assertThat(result.processedPages()).isEqualTo(2);
        assertThat(result.suggestions().email().getFirst().page()).isEqualTo(2);
    }
    @Test void noTextAndNoRecognizedDataIsNoData() throws Exception {
        assertThat(new PdfCvExtractor(image -> "").extract(pdf("")).outcome()).isEqualTo(Outcome.NO_DATA);
    }
    @Test void failingOcrRetainsAvailableSuggestions() throws Exception {
        var result = new PdfCvExtractor(image -> { throw new IOException("private filename and CV content"); }).extract(pdf("Email: alice@example.test"));
        assertThat(result.outcome()).isEqualTo(Outcome.PARTIAL);
        assertThat(result.suggestions().email()).hasSize(1); assertThat(result.warnings()).contains("OCR_FAILED");
        assertThat(result.toString()).doesNotContain("private", "alice");
    }
    @Test void failedOcrWithoutAnyDataIsFailed() throws Exception {
        var result = new PdfCvExtractor(image -> { throw new TesseractOcrEngine.Failure("OCR_TIMEOUT"); }).extract(pdf(""));
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED); assertThat(result.warnings()).contains("OCR_TIMEOUT");
    }
    @Test void neverReadsPastPageFiveAndWarns() throws Exception {
        var calls = new AtomicInteger();
        var result = new PdfCvExtractor(image -> { calls.incrementAndGet(); return ""; }).extract(pdf("", "", "", "", "", CONTACT));
        assertThat(calls).hasValue(5); assertThat(result.totalPages()).isEqualTo(6);
        assertThat(result.processedPages()).isEqualTo(5); assertThat(result.suggestions().email()).isEmpty();
        assertThat(result.warnings()).contains("FIRST_FIVE_PAGES_ONLY");
    }
    @Test void oversizedPageDoesNotAllocateAnUnboundedImage() throws Exception {
        byte[] bytes;
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(new PDRectangle(10000, 10000))); doc.save(output); bytes = output.toByteArray();
        }
        var result = new PdfCvExtractor(image -> { throw new AssertionError("must not render"); }).extract(bytes);
        assertThat(result.warnings()).contains("IMAGE_LIMIT"); assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
    }
    @Test void invalidEncryptedEmptyAndOverLimitFilesFailSafely() throws Exception {
        var extractor = new PdfCvExtractor(image -> "");
        assertThat(extractor.extract(null).warnings()).contains("PDF_INVALID");
        assertThat(extractor.extract(new byte[0]).outcome()).isEqualTo(Outcome.FAILED);
        assertThat(extractor.extract(new byte[CvExtractionLimits.MAX_BYTES + 1]).warnings()).contains("FILE_LIMIT");
        assertThat(extractor.extract("%PDF-1.7\nprivate broken data".getBytes(StandardCharsets.UTF_8)).warnings()).contains("PDF_INVALID");
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            document.save(output); assertThat(extractor.extract(output.toByteArray()).outcome()).isEqualTo(Outcome.FAILED);
        }
    }
    @ParameterizedTest @ValueSource(strings={"PK DOCX", "<html>cv</html>", "random"})
    void unsupportedInputIsNotAnExtractionCrash(String input) {
        assertThat(new PdfCvExtractor(image -> "").extract(input.getBytes(StandardCharsets.UTF_8)).outcome()).isEqualTo(Outcome.UNSUPPORTED);
    }
    @Test void extractionCannotMutateInputBytes() throws Exception {
        byte[] bytes = pdf(CONTACT); byte[] before = bytes.clone();
        new PdfCvExtractor(image -> "").extract(bytes);
        assertThat(bytes).isEqualTo(before);
    }
    @Test void ocrTextBudgetIsEnforcedAndPartialResultIsVisible() throws Exception {
        var result = new PdfCvExtractor(image -> "Email: alice@example.test\n" + "x".repeat(CvExtractionLimits.MAX_TEXT))
                .extract(pdf(""));
        assertThat(result.outcome()).isEqualTo(Outcome.PARTIAL);
        assertThat(result.warnings()).contains("TEXT_LIMIT");
        assertThat(result.suggestions().email()).hasSize(1);
    }
    @Test void nullOcrResultIsSafeFailure() throws Exception {
        var result = new PdfCvExtractor(image -> null).extract(pdf(""));
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.warnings()).contains("OCR_FAILED");
    }
    @Test void pixelBudgetDoesNotChangeTheTenMebibyteIntakeContract() {
        assertThat(CvExtractionLimits.MAX_BYTES).isEqualTo(vn.ttcs.recruitment.application.CvValidator.MAX_BYTES);
        assertThat(CvExtractionLimits.MAX_PAGES).isEqualTo(5);
    }
}
