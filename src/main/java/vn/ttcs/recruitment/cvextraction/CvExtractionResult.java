package vn.ttcs.recruitment.cvextraction;

import java.util.List;

/** Suggestions only. No object in this package updates an application. */
public record CvExtractionResult(String extractorVersion, Outcome outcome, int processedPages, int totalPages,
                                 Suggestions suggestions, List<String> warnings) {
    public static final String VERSION = "pdf-contact-v2";
    public enum Outcome { COMPLETE, PARTIAL, NO_DATA, UNSUPPORTED, FAILED }
    public enum Source { TEXT, OCR }
    public record Candidate(String value, String normalizedValue, String reason, int page, Source source,
                            boolean preferred) {
        @Override public String toString() { return "Candidate[personal data redacted]"; }
    }
    public record Suggestions(List<Candidate> fullName, List<Candidate> email, List<Candidate> phone) {
        public Suggestions { fullName = List.copyOf(fullName); email = List.copyOf(email); phone = List.copyOf(phone); }
        @Override public String toString() { return "Suggestions[personal data redacted]"; }
    }
    public CvExtractionResult { warnings = List.copyOf(warnings); }
    static Suggestions empty() { return new Suggestions(List.of(), List.of(), List.of()); }
    static CvExtractionResult failure(String code) {
        return new CvExtractionResult(VERSION, Outcome.FAILED, 0, 0, empty(), List.of(code));
    }
    @Override public String toString() { return "CvExtractionResult[outcome=" + outcome + ", personal data redacted]"; }
}
