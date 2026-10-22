package vn.ttcs.recruitment.cvextraction;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static vn.ttcs.recruitment.cvextraction.CvExtractionResult.*;

/** Standalone engine, never a Spring bean. Production callers must use the bounded extraction container. */
public final class PdfCvExtractor {
    private final OcrEngine ocr;
    public PdfCvExtractor(OcrEngine ocr) { this.ocr = java.util.Objects.requireNonNull(ocr); }

    public CvExtractionResult extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return failure("PDF_INVALID");
        if (bytes.length > CvExtractionLimits.MAX_BYTES) return failure("FILE_LIMIT");
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') {
            return new CvExtractionResult(VERSION, Outcome.UNSUPPORTED, 0, 0, empty(), List.of("PDF_REQUIRED"));
        }
        try (var input = new RandomAccessReadBuffer(bytes); var document = new PDFParser(input).parse(false)) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 1000) return failure("PDF_INVALID");
            return extractDocument(document);
        } catch (Exception exception) {
            // PDFBox exceptions can include content; never expose the cause or log the exception.
            return failure("PDF_INVALID");
        }
    }

    private CvExtractionResult extractDocument(PDDocument document) throws IOException {
        int total = document.getNumberOfPages();
        int pages = Math.min(total, CvExtractionLimits.MAX_PAGES);
        var warnings = new LinkedHashSet<String>();
        if (total > pages) warnings.add("FIRST_FIVE_PAGES_ONLY");
        var text = new ArrayList<ContactSuggestionExtractor.PageText>();
        int remaining = CvExtractionLimits.MAX_TEXT;
        int processed = 0;
        boolean ocrFailed = false;
        for (int number = 1; number <= pages; number++) {
            if (remaining <= 0) { warnings.add("TEXT_LIMIT"); break; }
            var writer = new BoundedText(remaining);
            var stripper = new LimitedStripper(document.getPage(number - 1));
            stripper.setSortByPosition(true);
            stripper.setStartPage(number); stripper.setEndPage(number);
            try { stripper.writeText(document, writer); }
            catch (TextLimit exception) { warnings.add("TEXT_LIMIT"); }
            String extracted = writer.toString();
            text.add(new ContactSuggestionExtractor.PageText(number, Source.TEXT, extracted, stripper.positions()));
            remaining -= extracted.length();
            processed++;
            var pageSuggestions = new ContactSuggestionExtractor().extract(List.of(
                    new ContactSuggestionExtractor.PageText(number, Source.TEXT, extracted, stripper.positions())));
            // A dense body does not imply that an image containing contact details has a text layer.
            boolean sparse = extracted.codePoints().filter(Character::isLetterOrDigit).count() < 80;
            boolean mixed = !pageSuggestions.complete() && containsImage(document.getPage(number - 1).getResources(), 0);
            if ((sparse || mixed) && remaining > 0) {
                try {
                    PDPage page = document.getPage(number - 1);
                    checkImageSize(page);
                    var renderer = new PDFRenderer(document);
                    renderer.setSubsamplingAllowed(true);
                    var image = renderer.renderImageWithDPI(number - 1, CvExtractionLimits.DPI, ImageType.GRAY);
                    try {
                        OcrEngine.Page recognizedPage = ocr.recognizePage(image);
                        if (recognizedPage == null) throw new TesseractOcrEngine.Failure("OCR_FAILED");
                        String recognized = recognizedPage.text();
                        if (recognized.length() > remaining) { recognized = recognized.substring(0, remaining); warnings.add("TEXT_LIMIT"); }
                        text.add(new ContactSuggestionExtractor.PageText(number, Source.OCR, recognized, recognizedPage.positions()));
                        remaining -= recognized.length();
                    } finally { image.flush(); }
                } catch (TesseractOcrEngine.Failure exception) { warnings.add(exception.code()); ocrFailed = true; }
                catch (IOException | RuntimeException exception) { warnings.add("OCR_FAILED"); ocrFailed = true; }
            }
        }
        var extraction = new ContactSuggestionExtractor().extract(text);
        warnings.addAll(extraction.warnings());
        Suggestions suggestions = extraction.suggestions();
        boolean any = !suggestions.fullName().isEmpty() || !suggestions.email().isEmpty() || !suggestions.phone().isEmpty();
        Outcome outcome = !any ? ocrFailed ? Outcome.FAILED : Outcome.NO_DATA
                : extraction.complete() && !ocrFailed && !warnings.contains("TEXT_LIMIT") ? Outcome.COMPLETE : Outcome.PARTIAL;
        return new CvExtractionResult(VERSION, outcome, processed, total, suggestions, List.copyOf(warnings));
    }

    private static boolean containsImage(PDResources resources, int depth) throws IOException {
        if (resources == null || depth > 4) return false;
        int count = 0;
        for (var name : resources.getXObjectNames()) {
            if (++count > 256) return true; // Conservative fallback; the sandbox still bounds parse/render work.
            if (resources.isImageXObject(name)) return true;
            var object = resources.getXObject(name);
            if (object instanceof PDFormXObject form && containsImage(form.getResources(), depth + 1)) return true;
        }
        return false;
    }

    private static void checkImageSize(PDPage page) throws TesseractOcrEngine.Failure {
        double scale = CvExtractionLimits.DPI / 72d * page.getUserUnit();
        double width = Math.ceil(page.getCropBox().getWidth() * scale);
        double height = Math.ceil(page.getCropBox().getHeight() * scale);
        if (!Double.isFinite(width) || !Double.isFinite(height) || width < 1 || height < 1
                || width > CvExtractionLimits.MAX_DIMENSION || height > CvExtractionLimits.MAX_DIMENSION
                || width * height > CvExtractionLimits.MAX_PIXELS) throw new TesseractOcrEngine.Failure("IMAGE_LIMIT");
    }
    private static final class TextLimit extends IOException { }
    private static final class BoundedText extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private int lineIndex;
        BoundedText(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            int available = limit - text.length();
            int written = Math.min(length, available);
            text.append(chars, offset, written);
            for (int i = offset; i < offset + written; i++) if (chars[i] == '\n') lineIndex++;
            if (length > available) throw new TextLimit();
        }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return text.toString(); }
        int lineIndex() { return lineIndex; }
    }
    private static final class LimitedStripper extends PDFTextStripper {
        private int glyphs;
        private float previousEnd = Float.NaN;
        private float previousY = Float.NaN;
        private final double pageWidth, pageHeight;
        private final java.util.Map<Integer, OcrEngine.LinePosition> positions = new java.util.LinkedHashMap<>();
        LimitedStripper(PDPage page) throws IOException {
            pageWidth = page.getCropBox().getWidth(); pageHeight = page.getCropBox().getHeight();
        }
        List<OcrEngine.LinePosition> positions() { return List.copyOf(positions.values()); }
        @Override protected void processTextPosition(TextPosition position) {
            if (++glyphs > CvExtractionLimits.MAX_TEXT) throw new IllegalArgumentException("PDF text complexity limit");
            super.processTextPosition(position);
        }
        @Override protected void writeString(String value, List<TextPosition> positions) throws IOException {
            if (!positions.isEmpty()) {
                var first = positions.getFirst();
                // Preserve a large column gap as a line boundary instead of joining a name with another column.
                if (!Float.isNaN(previousEnd) && Math.abs(first.getYDirAdj() - previousY) < 2
                        && first.getXDirAdj() - previousEnd > Math.max(24, first.getFontSizeInPt() * 2)) {
                    getOutput().write('\n');
                }
                var last = positions.getLast();
                previousEnd = last.getXDirAdj() + last.getWidthDirAdj(); previousY = last.getYDirAdj();
                if (getOutput() instanceof BoundedText output && this.positions.size() < CvExtractionLimits.MAX_OCR_LINES) {
                    double left = Math.clamp(first.getXDirAdj() / pageWidth, 0, 1);
                    double top = Math.clamp(first.getYDirAdj() / pageHeight, 0, 1);
                    double height = Math.clamp(positions.stream().mapToDouble(TextPosition::getHeightDir).max().orElse(0) / pageHeight, 0, 1);
                    if (Double.isFinite(left) && Double.isFinite(top) && Double.isFinite(height) && height > 0) {
                        OcrEngine.LinePosition old = this.positions.get(output.lineIndex());
                        this.positions.put(output.lineIndex(), new OcrEngine.LinePosition(output.lineIndex(),
                                old == null ? left : Math.min(old.left(), left), old == null ? top : Math.min(old.top(), top),
                                old == null ? height : Math.max(old.height(), height)));
                    }
                }
            }
            super.writeString(value, positions);
        }
    }
}
