package vn.ttcs.recruitment.account.importing;

/**
 * Columns of the staff import sheet, in file order. The Vietnamese header is what administrators see in row 1;
 * the key is the stable name used by the API. Both are part of the documented file contract.
 */
public enum StaffImportColumn {
    EMAIL("email", "Email", true,
            "Email đăng nhập, tối đa 254 ký tự, chưa có tài khoản nào dùng. "
                    + "Hệ thống bỏ khoảng trắng đầu/cuối và đổi về chữ thường.",
            "nguyen.van.an@example.com"),
    FULL_NAME("fullName", "Họ và tên", true,
            "Tối đa 255 ký tự.",
            "Nguyễn Văn An"),
    ROLES("roles", "Vai trò", true,
            "Một hoặc nhiều mã trong bảng \"Mã vai trò hợp lệ\" bên dưới, viết in hoa, cách nhau bằng dấu phẩy.",
            "RECRUITER, INTERVIEWER"),
    DEPARTMENT_CODE("departmentCode", "Mã phòng ban", false,
            "Mã của một phòng ban đang áp dụng, ghi đúng chữ hoa/thường như trong danh mục phòng ban. "
                    + "Để trống nếu chưa gán phòng ban.",
            "HR"),
    PHONE("phone", "Số điện thoại", false,
            "Số di động 10 chữ số bắt đầu bằng 03, 05, 07, 08, 09 hoặc số cố định 11 chữ số bắt đầu bằng 02; "
                    + "có thể ghi +84 thay cho số 0 đầu.",
            "0912345678"),
    DISPLAY_TITLE("displayTitle", "Chức danh hiển thị", false,
            "Chữ tự do hiển thị trên hồ sơ, tối đa 120 ký tự; không phải mã trong danh mục chức danh.",
            "Chuyên viên tuyển dụng");

    private final String key;
    private final String header;
    private final boolean required;
    private final String rule;
    private final String example;

    StaffImportColumn(String key, String header, boolean required, String rule, String example) {
        this.key = key;
        this.header = header;
        this.required = required;
        this.rule = rule;
        this.example = example;
    }

    public String key() { return key; }
    public String header() { return header; }
    public boolean required() { return required; }
    public String rule() { return rule; }
    public String example() { return example; }
}
