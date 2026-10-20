package vn.ttcs.recruitment.talentpool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TalentPoolEvidenceTest {
    @Test
    void everyEvidenceHasAVietnameseNameAndAStoredValue() {
        for (TalentPoolEvidence evidence : TalentPoolEvidence.values()) {
            String name = switch (evidence) {
                case HIRING_DECISION -> "Quyết định không tuyển sau phỏng vấn";
                case OFFER_DECLINED -> "Ứng viên từ chối offer";
                case FINAL_STAGE_REJECTION -> "Bị loại ở vòng phỏng vấn hoặc offer";
            };
            assertThat(evidence.displayName()).isEqualTo(name);
            assertThat(TalentPoolEvidence.valueOf(evidence.name())).isSameAs(evidence);
        }
    }
}
