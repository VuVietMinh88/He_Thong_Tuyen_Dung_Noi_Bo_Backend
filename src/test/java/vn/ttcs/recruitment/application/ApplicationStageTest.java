package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStageTest {
    @Test
    void onlyNonFinalApplicationsBelongToTheActivePipeline() {
        assertThat(Arrays.stream(ApplicationStage.values()).filter(ApplicationStage::active).toList())
                .containsExactly(ApplicationStage.NEW, ApplicationStage.SCREENING, ApplicationStage.INTERVIEW, ApplicationStage.OFFER);
        assertThat(Arrays.stream(ApplicationStage.values()).filter(stage -> !stage.active()).toList())
                .containsExactly(ApplicationStage.HIRED, ApplicationStage.REJECTED, ApplicationStage.WITHDRAWN);
    }
}
