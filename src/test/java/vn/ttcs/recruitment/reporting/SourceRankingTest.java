package vn.ttcs.recruitment.reporting;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.reporting.SourceRanking.Counts;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 581: the best source is the one with the best outcomes on a meaningful sample, not the largest one. */
class SourceRankingTest {

    @Test
    void qualityBeatsVolumeAndASmallSampleIsListedButNotRanked() {
        var large = source("Trang tuyển dụng", 100, 20, 2);                          // hire rate 2 %
        var good = new Counts("INTERNAL_REFERRAL", "Giới thiệu nội bộ", true, 10, 6, 2);  // hire rate 20 %
        var lucky = source("Hội chợ", 1, 1, 1);                                      // 100 %, but one application
        var unknown = new Counts("UNKNOWN", "Không rõ nguồn", false, 50, 10, 5);     // 10 %, not a source
        var ranked = SourceRanking.rank(List.of(large, good, lucky, unknown), 5);

        assertThat(ranked).extracting(row -> row.counts().name())
                .containsExactly("Giới thiệu nội bộ", "Trang tuyển dụng", "Không rõ nguồn", "Hội chợ");
        assertThat(ranked.get(0).qualityRank()).isEqualTo(1);
        assertThat(ranked.get(0).hireRate()).isEqualByComparingTo("0.2000");
        assertThat(ranked.get(0).screeningPassRate()).isEqualByComparingTo("0.6000");
        assertThat(ranked.get(1).qualityRank()).isEqualTo(2);
        assertThat(ranked.get(2).qualityRank()).isNull();                // the unknown bucket is never ranked
        assertThat(ranked.get(2).meetsMinimumSample()).isTrue();
        assertThat(ranked.get(3).qualityRank()).isNull();
        assertThat(ranked.get(3).meetsMinimumSample()).isFalse();
        assertThat(ranked.get(3).hireRate()).isEqualByComparingTo("1.0000");
    }

    private static Counts source(String name, long applications, long passed, long hired) {
        return new Counts(UUID.randomUUID().toString(), name, true, applications, passed, hired);
    }

    @Test
    void tiesFallToTheScreeningRateThenTheLargerSampleThenTheName() {
        var a = source("B nguồn", 10, 5, 1);     // 10 % hire, 50 % pass
        var b = source("A nguồn", 20, 8, 2);     // 10 % hire, 40 % pass
        var c = source("C nguồn", 20, 10, 2);    // 10 % hire, 50 % pass, larger
        var d = source("D nguồn", 20, 10, 2);    // identical to c: by name
        assertThat(SourceRanking.rank(List.of(a, b, c, d), 1)).extracting(row -> row.counts().name())
                .containsExactly("C nguồn", "D nguồn", "B nguồn", "A nguồn");
        assertThat(SourceRanking.rank(List.of(), 5)).isEmpty();
        var empty = SourceRanking.rank(List.of(source("Trống", 0, 0, 0)), 1).getFirst();
        assertThat(empty.hireRate()).isNull();
        assertThat(empty.qualityRank()).isNull();
    }
}
