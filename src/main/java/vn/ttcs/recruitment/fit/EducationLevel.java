package vn.ttcs.recruitment.fit;

/**
 * Task 412 (story S5-09, "bằng cấp"): the education levels a position may require and an application may hold, in
 * increasing order: a level meets every requirement at or below it. The names are the stored values (V65_3).
 */
public enum EducationLevel {
    HIGH_SCHOOL("Tốt nghiệp THPT"),
    COLLEGE("Cao đẳng"),
    BACHELOR("Đại học"),
    MASTER("Thạc sĩ"),
    DOCTORATE("Tiến sĩ");

    private final String displayName;

    EducationLevel(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }

    public boolean meets(EducationLevel required) {
        return compareTo(required) >= 0;
    }
}
