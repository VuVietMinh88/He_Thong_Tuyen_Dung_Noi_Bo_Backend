package vn.ttcs.recruitment.talentpool;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.common.ApiException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TalentPoolSearchTest {
    @Test
    void titlesAndSkillsBecomeCaseAndAccentInsensitiveKeys() {
        var search = TalentPoolSearch.of("  Lập Trình ", List.of("JAVA", "java", "Kế toán"), 2, 10);
        assertThat(search.titleKey()).isEqualTo("lap trinh");
        assertThat(search.titlePattern()).isEqualTo("%lap trinh%");
        assertThat(search.skillKeys()).containsExactly("java", "ke toan");
        assertThat(search.offset()).isEqualTo(20);
        assertThat(TalentPoolSearch.of(null, null, 0, 20).titleKey()).isNull();
        assertThat(TalentPoolSearch.of("50%_x!", null, 0, 20).titlePattern()).isEqualTo("%50!%!_x!!%");
    }

    @Test
    void boundsAreChecked() {
        for (Runnable invalid : List.<Runnable>of(() -> TalentPoolSearch.of("a", null, 0, 20),
                () -> TalentPoolSearch.of(null, null, -1, 20), () -> TalentPoolSearch.of(null, null, 0, 101),
                () -> TalentPoolSearch.of(null, List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"), 0, 20))) {
            assertThatThrownBy(invalid::run).isInstanceOf(ApiException.class);
        }
    }
}
