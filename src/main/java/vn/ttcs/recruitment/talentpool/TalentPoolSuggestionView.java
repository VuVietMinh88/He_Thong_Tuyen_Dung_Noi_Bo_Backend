package vn.ttcs.recruitment.talentpool;

import java.util.List;
import java.util.UUID;

/**
 * Task 400 (story S5-06, "Khi mở vị trí mới, hệ thống gợi ý ứng viên phù hợp từ kho"): the pooled candidates that may
 * fit a requisition, with what they were matched on. basisTitles are the position's name and the posting's title,
 * basisSkills the position's required skills (task 412). A suggestion is advice only: nothing is created or changed.
 */
public record TalentPoolSuggestionView(UUID requisitionId, UUID positionId, List<String> basisTitles,
                                       List<String> basisSkills, String advice, List<Suggestion> items) {
    static final String ADVICE = "Gợi ý từ kho tiềm năng chỉ để tham khảo; hệ thống không tự tạo hồ sơ ứng tuyển hay liên"
            + " hệ ứng viên.";

    public record Suggestion(TalentPoolEntryView entry, List<String> matchedTitles, List<String> matchedSkills) { }
}
