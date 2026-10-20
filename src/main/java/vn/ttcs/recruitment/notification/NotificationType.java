package vn.ttcs.recruitment.notification;

/**
 * Task 543/549: what a notification is about; also the unit of the per-user preferences. APPROVAL_PENDING is
 * mandatory: neither channel can be turned off (task 550 refuses it, task 551 delivers it whatever is stored).
 */
public enum NotificationType {
    APPROVAL_PENDING(true, "Yêu cầu chờ bạn duyệt"),
    RECRUITER_ASSIGNED(false, "Được phân công vị trí mới"),
    INTERVIEW_INVITED(false, "Được mời phỏng vấn"),
    OFFER_RESPONDED(false, "Ứng viên phản hồi offer"),
    SLA_OVERDUE(false, "Hồ sơ quá hạn xử lý");

    private final boolean mandatory;
    private final String displayName;

    NotificationType(boolean mandatory, String displayName) {
        this.mandatory = mandatory;
        this.displayName = displayName;
    }

    public boolean mandatory() { return mandatory; }

    public String displayName() { return displayName; }
}
