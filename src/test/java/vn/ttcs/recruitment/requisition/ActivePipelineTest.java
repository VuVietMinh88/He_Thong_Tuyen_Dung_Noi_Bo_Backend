package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 291: what a pipeline source may answer, the 409 message, and the honest default without a candidate module.
class ActivePipelineTest {
    private static final ActivePipeline.Stage SCREENING = new ActivePipeline.Stage("SCREENING", "Sàng lọc", 1);
    private static final ActivePipeline.Stage INTERVIEW = new ActivePipeline.Stage("INTERVIEW", "Phỏng vấn", 2);

    @Test
    void noneIsAnEmptyPipeline() {
        assertThat(ActivePipeline.NONE.count()).isZero();
        assertThat(ActivePipeline.NONE.stages()).isEmpty();
        assertThat(ActivePipeline.NONE).isEqualTo(new ActivePipeline(0, List.of()));
    }

    // "Per-stage breakdown if meaningful": a source that only counts answers without inventing a stage.
    @Test
    void aCountWithoutABreakdownIsAccepted() {
        ActivePipeline counted = new ActivePipeline(2, List.of());

        assertThat(counted.count()).isEqualTo(2);
        assertThat(counted.stages()).isEmpty();
    }

    @Test
    void aBreakdownAddsUpToTheCountInPipelineOrder() {
        ActivePipeline pipeline = new ActivePipeline(3, List.of(SCREENING, INTERVIEW));

        assertThat(pipeline.stages()).containsExactly(SCREENING, INTERVIEW);
        for (int count : List.of(2, 4, 0)) {
            assertThatThrownBy(() -> new ActivePipeline(count, List.of(SCREENING, INTERVIEW)))
                    .as("count " + count).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A breakdown must add up to the count");
        }
    }

    @Test
    void invalidPipelinesAreRefused() {
        assertThatThrownBy(() -> new ActivePipeline(-1, List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A pipeline count is never negative");
        assertThatThrownBy(() -> new ActivePipeline(2, List.of(SCREENING, SCREENING)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A stage is listed once");
        assertThatThrownBy(() -> new ActivePipeline(2, List.of(SCREENING,
                new ActivePipeline.Stage("SCREENING", "Sàng lọc lại", 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A stage is listed once");
        assertThatThrownBy(() -> new ActivePipeline(1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aStageHasACodeANameAndCandidates() {
        for (int count : List.of(0, -1)) {
            assertThatThrownBy(() -> new ActivePipeline.Stage("SCREENING", "Sàng lọc", count))
                    .as("count " + count).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Only stages with candidates are listed");
        }
        for (String blank : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> new ActivePipeline.Stage(blank, "Sàng lọc", 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("A stage has a code and a name");
            assertThatThrownBy(() -> new ActivePipeline.Stage("SCREENING", blank, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("A stage has a code and a name");
        }
    }

    // The source's list cannot change the pipeline afterwards, and the pipeline's list cannot be changed.
    @Test
    void theStagesAreACopy() {
        List<ActivePipeline.Stage> source = new ArrayList<>(List.of(SCREENING, INTERVIEW));
        ActivePipeline pipeline = new ActivePipeline(3, source);

        source.clear();

        assertThat(pipeline.stages()).containsExactly(SCREENING, INTERVIEW);
        assertThatThrownBy(() -> pipeline.stages().add(SCREENING))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theConflictMessageSaysHowManyAndWhatTheCallerConfirms() {
        assertThat(ActivePipelineConflictException.message(RequisitionStatusChange.FILL, 3)).isEqualTo(
                "Yêu cầu tuyển dụng còn 3 ứng viên đang trong quy trình tuyển dụng. Vui lòng xử lý các ứng viên này "
                        + "trước, hoặc xác nhận vẫn đóng yêu cầu vì đã tuyển đủ.");
        // One candidate is singular: "ứng viên này", without the plural "các".
        assertThat(ActivePipelineConflictException.message(RequisitionStatusChange.CANCEL, 1)).isEqualTo(
                "Yêu cầu tuyển dụng còn 1 ứng viên đang trong quy trình tuyển dụng. Vui lòng xử lý ứng viên này "
                        + "trước, hoặc xác nhận vẫn hủy yêu cầu.");
        assertThat(ActivePipelineConflictException.message(RequisitionStatusChange.CANCEL, 2)).isEqualTo(
                "Yêu cầu tuyển dụng còn 2 ứng viên đang trong quy trình tuyển dụng. Vui lòng xử lý các ứng viên này "
                        + "trước, hoặc xác nhận vẫn hủy yêu cầu.");
        assertThat(ActivePipelineConflictException.message(RequisitionStatusChange.FILL, 1)).isEqualTo(
                "Yêu cầu tuyển dụng còn 1 ứng viên đang trong quy trình tuyển dụng. Vui lòng xử lý ứng viên này "
                        + "trước, hoặc xác nhận vẫn đóng yêu cầu vì đã tuyển đủ.");

        ActivePipeline pipeline = new ActivePipeline(3, List.of(SCREENING, INTERVIEW));
        var conflict = new ActivePipelineConflictException(RequisitionStatusChange.FILL, pipeline);
        assertThat(conflict.getCode()).isEqualTo("REQUISITION_HAS_ACTIVE_CANDIDATES");
        assertThat(conflict.getStatus().value()).isEqualTo(409);
        assertThat(conflict.getFieldErrors()).isEmpty();
        assertThat(conflict.activePipeline()).isEqualTo(pipeline);
        assertThat(conflict.body()).isEqualTo(new ActivePipelineConflictException.Body(
                "REQUISITION_HAS_ACTIVE_CANDIDATES", conflict.getMessage(), Map.of(), pipeline));
    }

    @ParameterizedTest
    @EnumSource(value = RequisitionStatusChange.class, names = {"PAUSE", "RESUME"})
    void changesWithoutConfirmationHaveNoMessage(RequisitionStatusChange change) {
        assertThatThrownBy(() -> ActivePipelineConflictException.message(change, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(change + " needs no confirmation");
    }

    // Without a candidate module the backend knows of nobody: 0 for every requisition, which means "no data".
    @Test
    void withoutACandidateModuleNobodyIsKnown() {
        var source = new NoCandidateModulePipelineSource();

        for (int index = 0; index < 3; index++) {
            assertThat(source.activePipeline(UUID.randomUUID())).isEqualTo(ActivePipeline.NONE);
        }
    }
}
