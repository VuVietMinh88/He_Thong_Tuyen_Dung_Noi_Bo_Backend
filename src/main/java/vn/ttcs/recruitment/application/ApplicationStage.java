package vn.ttcs.recruitment.application;

public enum ApplicationStage {
    NEW("Hồ sơ mới", true), SCREENING("Sàng lọc", true), INTERVIEW("Phỏng vấn", true),
    OFFER("Đề xuất offer", true), HIRED("Nhận việc", false), REJECTED("Loại", false), WITHDRAWN("Ứng viên rút", false);

    private final String displayName;
    private final boolean active;

    ApplicationStage(String displayName, boolean active) {
        this.displayName = displayName;
        this.active = active;
    }

    public String displayName() { return displayName; }
    public boolean active() { return active; }
}
