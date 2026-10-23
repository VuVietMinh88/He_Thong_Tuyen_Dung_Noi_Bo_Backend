package vn.ttcs.recruitment.fit;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 414 (story S5-09, "Điểm chỉ để gợi ý sắp xếp, không tự động loại ứng viên"): one page of the ranking suggestion of
 * a requisition. rank is the position in the whole ranking (1 = best). fit is null only for an application without a
 * stored result. No contact detail, CV, cover letter or note is part of an item.
 */
public record FitRankingView(UUID requisitionId, UUID positionId, RequiredCriteriaView criteria, String advice,
                             List<Item> items, long total, int page, int size, boolean hasNext) {
    static final String ADVICE = "Điểm phù hợp chỉ để gợi ý thứ tự xem hồ sơ; hệ thống không tự loại hay chuyển giai đoạn"
            + " ứng viên.";

    public record Item(long rank, UUID applicationId, UUID candidateId, String fullName, ApplicationStage stage,
                       String stageName, Instant receivedAt, ApplicationFitView.FitView fit) {
        @Override public String toString() { return "Item[" + applicationId + ", personal data redacted]"; }
    }
}
