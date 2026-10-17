package vn.ttcs.recruitment.jobposting;

import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Task 309: rows of JobPostingReadRepository.SELECT_ROW for unit tests. The only place in the tests that calls the
// JobPostingRow constructor, so a new column of the row query changes this factory and no test.
final class JobPostingRowFixtures {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000031");
    static final UUID REQUISITION = UUID.fromString("00000000-0000-0000-0000-000000000011");
    static final UUID CREATOR = UUID.fromString("00000000-0000-0000-0000-000000000041");
    static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000042");
    // Task 316: the ids of the row's position, department and catalog values; the portal shows only their names.
    static final UUID POSITION = UUID.fromString("00000000-0000-0000-0000-000000000051");
    static final UUID DEPARTMENT = UUID.fromString("00000000-0000-0000-0000-000000000061");
    static final UUID WORK_LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000021");
    static final UUID EMPLOYMENT_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000022");

    private JobPostingRowFixtures() {
    }

    // A published posting of an approved requisition (20-22 million proposed), with benefits and HR's approval. The
    // publication trail of task 310 stays empty: the public view never reads it.
    static JobPostingRow row(boolean showSalary) {
        return row(showSalary, "Bảo hiểm đầy đủ.");
    }

    static JobPostingRow row(boolean showSalary, String benefits) {
        Instant created = Instant.parse("2026-10-11T03:00:00.123456Z");
        return new JobPostingRow(ID, REQUISITION, RequisitionStatus.APPROVED, JobPostingStatus.PUBLISHED, 5,
                "Lập trình viên Java (Hà Nội)", POSITION, "Lập trình viên", DEPARTMENT, "Phòng IT", 2,
                20_000_000L, 22_000_000L, "  Phát triển API tuyển dụng.\n\n- Spring Boot\n",
                "Tối thiểu 2 năm kinh nghiệm Java.", benefits,
                WORK_LOCATION, "Hà Nội", EMPLOYMENT_TYPE, "Toàn thời gian",
                LocalDate.of(2026, 11, 30), showSalary, CREATOR, "Trần Văn Tuyển", created, created, CREATOR,
                JobPostingEventType.APPROVED, "Đồng ý đăng tin.", REVIEWER, "Nguyễn Thị Hoa",
                Instant.parse("2026-10-11T03:03:00.333333Z"), null, null, null);
    }
}
