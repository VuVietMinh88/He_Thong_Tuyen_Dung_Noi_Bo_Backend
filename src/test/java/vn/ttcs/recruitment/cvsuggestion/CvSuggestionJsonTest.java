package vn.ttcs.recruitment.cvsuggestion;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ttcs.recruitment.cvextraction.CvExtractionResult;
import static org.assertj.core.api.Assertions.*;

class CvSuggestionJsonTest {
    @Test void roundTripsRedactedImmutableResult() {
        String encoded=CvSuggestionJson.unsupported();
        var result=CvSuggestionJson.read(encoded);
        assertThat(result.outcome()).isEqualTo(CvExtractionResult.Outcome.UNSUPPORTED);
        assertThat(result.toString()).doesNotContain("PDF_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","not-json"})
    void rejectsMalformedUntrustedOutput(String value) {
        assertThatThrownBy(()->CvSuggestionJson.read(value)).isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_RESULT");
    }
    @Test void rejectsWrongVersionInconsistentCompleteAndRawWarnings() {
        String value=CvSuggestionJson.unsupported();
        for (String bad : List.of(value.replace("pdf-contact-v2","older"),value.replace("UNSUPPORTED","COMPLETE"),
                value.replace("PDF_REQUIRED","private@example.test")," ".repeat(131073)))
            assertThatThrownBy(()->CvSuggestionJson.read(bad)).isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_RESULT");
    }
}
