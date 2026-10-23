package vn.ttcs.recruitment.talentpool;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.application.ApplicationStage;

import static org.assertj.core.api.Assertions.assertThat;

class TalentPoolCandidateStatusTest {
    @Test
    void everyStageSaysWhereItsCandidateStands() {
        for (ApplicationStage stage : ApplicationStage.values()) {
            TalentPoolCandidateStatus expected = stage.active() ? TalentPoolCandidateStatus.IN_PIPELINE
                    : stage == ApplicationStage.HIRED || stage == ApplicationStage.ONBOARDED
                    ? TalentPoolCandidateStatus.HIRED : TalentPoolCandidateStatus.AVAILABLE;
            assertThat(TalentPoolCandidateStatus.forStage(stage)).as(stage.name()).isEqualTo(expected);
        }
        assertThat(TalentPoolCandidateStatus.stagesSql(TalentPoolCandidateStatus.HIRED)).isEqualTo("'HIRED', 'ONBOARDED'");
        assertThat(TalentPoolCandidateStatus.stagesSql(TalentPoolCandidateStatus.IN_PIPELINE))
                .isEqualTo("'NEW', 'SCREENING', 'INTERVIEW', 'OFFER'");
    }

    @Test
    void everyStatusHasAVietnameseName() {
        for (TalentPoolCandidateStatus status : TalentPoolCandidateStatus.values()) {
            String name = switch (status) {
                case AVAILABLE -> "Có thể liên hệ";
                case IN_PIPELINE -> "Đang có hồ sơ đang xử lý";
                case HIRED -> "Đã nhận việc";
            };
            assertThat(status.displayName()).isEqualTo(name);
        }
    }
}
