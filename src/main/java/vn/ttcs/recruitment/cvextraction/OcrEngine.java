package vn.ttcs.recruitment.cvextraction;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/** An engine consumes one bounded page image. Raw text is transient and must never be logged or persisted. */
@FunctionalInterface
public interface OcrEngine {
    String recognize(BufferedImage image) throws IOException;

    /** Backwards-compatible adapter for engines without layout evidence. Never invent coordinates. */
    default Page recognizePage(BufferedImage image) throws IOException {
        return new Page(recognize(image), List.of());
    }

    /** Zero-based text line index and coordinates normalized to the rendered page, not reading order. */
    record LinePosition(int index, double left, double top, double height) {
        public LinePosition {
            if (index < 0 || !Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(height)
                    || left < 0 || left > 1 || top < 0 || top > 1 || height <= 0 || height > 1) {
                throw new IllegalArgumentException("Invalid page geometry");
            }
        }
    }

    record Page(String text, List<LinePosition> positions) {
        public Page {
            java.util.Objects.requireNonNull(text);
            positions = List.copyOf(positions);
            if (positions.size() > CvExtractionLimits.MAX_OCR_LINES) throw new IllegalArgumentException("Page line limit");
        }
        @Override public String toString() { return "OcrPage[content redacted]"; }
    }
}
