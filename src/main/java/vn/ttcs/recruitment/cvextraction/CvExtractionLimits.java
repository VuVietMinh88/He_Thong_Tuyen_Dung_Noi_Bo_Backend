package vn.ttcs.recruitment.cvextraction;

/** Deliberately fixed upper bounds; callers cannot enlarge them through document metadata or request fields. */
public final class CvExtractionLimits {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_PAGES = 5;
    public static final int MAX_TEXT = 100_000;
    public static final int MAX_LINE = 1_000;
    public static final int MAX_CANDIDATES = 10;
    public static final int DPI = 300;
    public static final long MAX_PIXELS = 12_000_000;
    public static final int MAX_DIMENSION = 5_000;
    public static final int OCR_SECONDS = 12;
    public static final int MAX_OCR_OUTPUT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_OCR_LINES = 2000;
    private CvExtractionLimits() { }
}
