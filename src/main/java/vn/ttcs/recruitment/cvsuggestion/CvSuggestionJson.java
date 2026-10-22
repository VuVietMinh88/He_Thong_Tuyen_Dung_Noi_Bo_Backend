package vn.ttcs.recruitment.cvsuggestion;

import tools.jackson.databind.json.JsonMapper;
import vn.ttcs.recruitment.cvextraction.CvExtractionResult;
import static vn.ttcs.recruitment.cvextraction.CvExtractionResult.*;

/** Treat sandbox stdout as untrusted. Never include it in errors or logs. */
public final class CvSuggestionJson {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private CvSuggestionJson() { }
    public static CvExtractionResult read(String value) {
        if (value==null || value.length()>131072) throw new IllegalArgumentException("INVALID_RESULT");
        try {
            var result=JSON.readValue(value, CvExtractionResult.class);
            if (!VERSION.equals(result.extractorVersion()) || result.outcome()==null
                    || result.processedPages()<0 || result.processedPages()>5
                    || result.totalPages()<result.processedPages() || result.totalPages()>1000
                    || result.warnings().size()>30) throw new IllegalArgumentException();
            for (String warning : result.warnings()) if (!warning.matches("[A-Z][A-Z0-9_]{0,59}")) throw new IllegalArgumentException();
            var fields=java.util.List.of(result.suggestions().fullName(),result.suggestions().email(),result.suggestions().phone());
            for (var field : fields) {
                if (field.size()>10 || field.stream().filter(Candidate::preferred).count()>1) throw new IllegalArgumentException();
                for (var candidate : field) if (candidate.value()==null || candidate.value().length()>1000
                        || candidate.normalizedValue()==null || candidate.normalizedValue().length()>1000
                        || candidate.reason()==null || !candidate.reason().matches("[A-Z][A-Z0-9_]{0,59}")
                        || candidate.source()==null || candidate.page()<1 || candidate.page()>result.processedPages())
                    throw new IllegalArgumentException();
            }
            boolean any=fields.stream().anyMatch(field -> !field.isEmpty());
            boolean complete=fields.stream().allMatch(field -> field.stream().anyMatch(Candidate::preferred));
            if (result.outcome()==Outcome.COMPLETE && !complete
                    || (result.outcome()==Outcome.NO_DATA || result.outcome()==Outcome.UNSUPPORTED) && any)
                throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException exception) { throw new IllegalArgumentException("INVALID_RESULT"); }
    }
    public static String write(CvExtractionResult result) { return JSON.writeValueAsString(result); }
    static String unsupported() {
        return write(new CvExtractionResult(VERSION, Outcome.UNSUPPORTED,0,0,
                new Suggestions(java.util.List.of(),java.util.List.of(),java.util.List.of()),java.util.List.of("PDF_REQUIRED")));
    }
}
