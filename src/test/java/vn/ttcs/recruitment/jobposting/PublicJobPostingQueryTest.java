package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.text.Normalizer;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// Task 315 (story S4-01): the filters and the keyword of the public job list as the SQL query receives them.
class PublicJobPostingQueryTest {
    private static final UUID DEPARTMENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TYPE = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void noParameterIsNoFilter() {
        PublicJobPostingQuery query = PublicJobPostingQuery.of(null, null, null, null);

        assertThat(query).isEqualTo(new PublicJobPostingQuery(null, null, null, ""));
        assertThatCode(query::requireValid).doesNotThrowAnyException();
    }

    @Test
    void theIdsAreKeptAndTheKeywordIsComposedAndItsSpacesReduced() {
        String decomposed = Normalizer.normalize("Kế toán", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("Kế toán");

        PublicJobPostingQuery query = PublicJobPostingQuery.of(DEPARTMENT, LOCATION, TYPE,
                " \t" + decomposed.replace(" ", "\r\n  ") + "  \n");

        assertThat(query).isEqualTo(new PublicJobPostingQuery(DEPARTMENT, LOCATION, TYPE, "Kế toán"));
        assertThat(PublicJobPostingQuery.of(null, null, null, " \t\n ").keyword()).isEmpty();
    }

    // The escape character first, so an escaped wildcard is not escaped again.
    @Test
    void theLikePatternFindsTheKeywordAnywhereWithItsWildcardsEscaped() {
        assertThat(PublicJobPostingQuery.of(null, null, null, "java").likePattern()).isEqualTo("%java%");
        assertThat(PublicJobPostingQuery.of(null, null, null, "100%_x!").likePattern()).isEqualTo("%100!%!_x!!%");
        assertThat(PublicJobPostingQuery.of(null, null, null, "!%").likePattern()).isEqualTo("%!!!%%");
    }

    @Test
    void aKeywordIsAtMost255CharactersWithoutControlCharacters() {
        assertThatCode(PublicJobPostingQuery.of(null, null, null, "x".repeat(255))::requireValid)
                .doesNotThrowAnyException();
        assertThatCode(PublicJobPostingQuery.of(null, null, null, "x" + " ".repeat(300) + "y")::requireValid)
                .doesNotThrowAnyException();

        assertRefused("x".repeat(256), "Từ khóa tìm kiếm tối đa 255 ký tự.");
        // Too long and with a control character: the length is reported.
        assertRefused("x".repeat(256) + (char) 0, "Từ khóa tìm kiếm tối đa 255 ký tự.");
        for (int control : new int[] {0x00, 0x01, 0x1B, 0x7F, 0x9F}) {
            assertRefused("kế" + (char) control + "toán", "Từ khóa tìm kiếm không được chứa ký tự điều khiển.");
        }
    }

    private static void assertRefused(String q, String fieldError) {
        ApiException refused = catchThrowableOfType(ApiException.class,
                PublicJobPostingQuery.of(null, null, null, q)::requireValid);

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(refused.getMessage()).isEqualTo("Từ khóa tìm kiếm tin tuyển dụng không hợp lệ.");
        assertThat(refused.getFieldErrors()).isEqualTo(Map.of("q", fieldError));
    }
}
