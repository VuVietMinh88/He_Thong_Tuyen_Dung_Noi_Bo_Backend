package vn.ttcs.recruitment.talentpool;

/**
 * Task 398 (story S5-06, "Ứng viên bị loại ở vòng cuối"): what shows that an application was rejected at a final
 * stage, read from the application's latest move into REJECTED (talent_pool_entries.evidence, V65_5). The names are the
 * stored values.
 */
public enum TalentPoolEvidence {
    HIRING_DECISION("Quyết định không tuyển sau phỏng vấn"),
    OFFER_DECLINED("Ứng viên từ chối offer"),
    FINAL_STAGE_REJECTION("Bị loại ở vòng phỏng vấn hoặc offer");

    private final String displayName;

    TalentPoolEvidence(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
