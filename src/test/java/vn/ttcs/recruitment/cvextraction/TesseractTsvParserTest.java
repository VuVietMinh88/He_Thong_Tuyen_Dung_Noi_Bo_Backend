package vn.ttcs.recruitment.cvextraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TesseractTsvParserTest {
    private static final String HEADER = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";
    private static String word(int block, int line, int number, int x, int y, String text) {
        return "5\t1\t" + block + "\t1\t" + line + "\t" + number + "\t" + x + "\t" + y + "\t100\t30\t90\t" + text + "\n";
    }
    @Test void combinesWordsAndSortsByPagePositionInsteadOfBlockReadingOrder() throws Exception {
        String tsv = HEADER + word(1, 1, 1, 20, 200, "LIÊN") + word(1, 1, 2, 130, 200, "HỆ")
                + word(2, 1, 1, 400, 50, "Nguyễn") + word(2, 1, 2, 510, 50, "Minh") + word(2, 1, 3, 620, 50, "Anh");
        var result = TesseractTsvParser.parse(tsv, 1000, 1000);
        assertThat(result.text()).isEqualTo("Nguyễn Minh Anh\nLIÊN HỆ");
        assertThat(result.positions()).hasSize(2);
        assertThat(result.positions().getFirst()).isEqualTo(new OcrEngine.LinePosition(0, .4, .05, .03));
        assertThat(result.toString()).doesNotContain("Nguyễn", "LIÊN");
    }
    @Test void noRecognizedWordsIsAnEmptyPage() throws Exception {
        var result = TesseractTsvParser.parse(HEADER + "1\t1\t0\t0\t0\t0\t0\t0\t1000\t1000\t-1\t\n", 1000, 1000);
        assertThat(result.text()).isEmpty(); assertThat(result.positions()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"bad header", "5\t1\t1", "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\tNaN\tprivate",
            "5\t1\t1\t1\t1\t1\t-1\t0\t10\t10\t90\tprivate", "5\t1\t1\t1\t1\t1\t999\t0\t10\t10\t90\tprivate",
            "5\t1\t1\t1\t1\t1\t0\t0\t2147483647\t10\t90\tprivate", "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t101\tprivate"})
    void rejectsMalformedAndOutOfImageGeometryWithoutExposingText(String row) {
        assertThatThrownBy(() -> TesseractTsvParser.parse(HEADER + row, 1000, 1000))
                .isInstanceOf(TesseractOcrEngine.Failure.class).hasMessageNotContaining("private");
    }
    @Test void capsWordAndLineData() {
        assertThatThrownBy(() -> TesseractTsvParser.parse(HEADER + word(1, 1, 1, 0, 0, "x".repeat(1300)), 1000, 1000))
                .isInstanceOf(TesseractOcrEngine.Failure.class);
        StringBuilder tsv = new StringBuilder(HEADER);
        for (int line = 1; line <= CvExtractionLimits.MAX_OCR_LINES + 1; line++) tsv.append(word(1, line, 1, 0, 0, "X"));
        assertThatThrownBy(() -> TesseractTsvParser.parse(tsv.toString(), 1000, 1000))
                .isInstanceOf(TesseractOcrEngine.Failure.class);
    }
    @Test void pageGeometryIsImmutableAndFinite() {
        var positions = new java.util.ArrayList<OcrEngine.LinePosition>();
        positions.add(new OcrEngine.LinePosition(0, .1, .1, .02));
        var page = new OcrEngine.Page("private", positions); positions.clear();
        assertThat(page.positions()).hasSize(1);
        assertThatThrownBy(() -> page.positions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new OcrEngine.LinePosition(0, 0, Double.NaN, .02)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OcrEngine.LinePosition(0, 0, 0, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
