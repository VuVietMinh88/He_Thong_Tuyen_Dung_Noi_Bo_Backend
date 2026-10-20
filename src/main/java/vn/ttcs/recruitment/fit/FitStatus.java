package vn.ttcs.recruitment.fit;

/**
 * Task 413 (story S5-09): the overall outcome (application_fit_results.status, V65_4): MET when every configured
 * criterion is met, NOT_MET when at least one is not, UNKNOWN when none failed but at least one could not be decided,
 * NO_CRITERIA when the position requires nothing.
 */
public enum FitStatus {
    MET("Đạt tiêu chí bắt buộc"),
    NOT_MET("Không đạt tiêu chí bắt buộc"),
    UNKNOWN("Chưa đủ dữ liệu để đánh giá"),
    NO_CRITERIA("Vị trí chưa có tiêu chí bắt buộc");

    private final String displayName;

    FitStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
