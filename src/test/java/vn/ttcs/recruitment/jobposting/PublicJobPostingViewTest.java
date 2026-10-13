package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Task 309: the one builder of what candidates see, used by the preview and by the public portal. A hidden salary is
// dropped on the server, and the public posting carries no editor data. Rows come from JobPostingRowFixtures only.
class PublicJobPostingViewTest {
    private static final List<String> PUBLIC_FIELDS = List.of("id", "title", "positionName", "departmentName",
            "headcount", "workLocationName", "employmentTypeName", "applicationDeadline", "salaryMin", "salaryMax",
            "jobDescription", "candidateRequirements", "benefits");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void everyPublicFieldComesFromTheRow() {
        JobPostingRow row = JobPostingRowFixtures.row(true);

        PublicJobPostingView view = PublicJobPostingView.of(row);

        assertThat(view).isEqualTo(new PublicJobPostingView(JobPostingRowFixtures.ID, "Lập trình viên Java (Hà Nội)",
                "Lập trình viên", "Phòng IT", 2, "Hà Nội", "Toàn thời gian", LocalDate.of(2026, 11, 30), 20_000_000L,
                22_000_000L, "  Phát triển API tuyển dụng.\n\n- Spring Boot\n", "Tối thiểu 2 năm kinh nghiệm Java.",
                "Bảo hiểm đầy đủ."));
    }

    @Test
    void theSalaryIsOnlyPublicWhenThePostingShowsIt() {
        PublicJobPostingView hidden = PublicJobPostingView.of(JobPostingRowFixtures.row(false));
        PublicJobPostingView shown = PublicJobPostingView.of(JobPostingRowFixtures.row(true));

        assertThat(hidden.salaryMin()).isNull();
        assertThat(hidden.salaryMax()).isNull();
        assertThat(shown.salaryMin()).isEqualTo(20_000_000L);
        assertThat(shown.salaryMax()).isEqualTo(22_000_000L);
        // Apart from the salary, both are the same posting.
        assertThat(hidden).isEqualTo(new PublicJobPostingView(shown.id(), shown.title(), shown.positionName(),
                shown.departmentName(), shown.headcount(), shown.workLocationName(), shown.employmentTypeName(),
                shown.applicationDeadline(), null, null, shown.jobDescription(), shown.candidateRequirements(),
                shown.benefits()));
    }

    // A hidden salary is not sent as null: the keys are left out of the JSON, like the salary band of a position.
    // Every other field is always sent, null included (a posting without benefits).
    @Test
    void hiddenSalaryKeysAreLeftOutOfTheJson() {
        JsonNode hidden = mapper.valueToTree(PublicJobPostingView.of(JobPostingRowFixtures.row(false, null)));
        JsonNode shown = mapper.valueToTree(PublicJobPostingView.of(JobPostingRowFixtures.row(true, null)));

        List<String> withoutSalary = new ArrayList<>(PUBLIC_FIELDS);
        withoutSalary.removeAll(List.of("salaryMin", "salaryMax"));
        assertThat(fieldNames(hidden)).containsExactlyElementsOf(withoutSalary);
        assertThat(hidden.path("benefits").isNull()).isTrue();
        assertThat(hidden.toString()).doesNotContain("20000000").doesNotContain("22000000");
        assertThat(fieldNames(shown)).containsExactlyElementsOf(PUBLIC_FIELDS);
        assertThat(shown.path("salaryMax").asLong()).isEqualTo(22_000_000L);
    }

    // Status, revision, requisition, creator, reviewer and every time stay with the editors (JobPostingView and the
    // preview's own fields).
    @Test
    void thePublicPostingCarriesNoEditorData() {
        assertThat(Arrays.stream(PublicJobPostingView.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactlyElementsOf(PUBLIC_FIELDS);
        JsonNode json = mapper.valueToTree(PublicJobPostingView.of(JobPostingRowFixtures.row(true)));
        assertThat(json.toString()).doesNotContain(JobPostingRowFixtures.REQUISITION.toString())
                .doesNotContain(JobPostingRowFixtures.CREATOR.toString())
                .doesNotContain(JobPostingRowFixtures.REVIEWER.toString())
                .doesNotContain("Trần Văn Tuyển").doesNotContain("Nguyễn Thị Hoa").doesNotContain("Đồng ý đăng tin.")
                .doesNotContain("PUBLISHED").doesNotContain("2026-10-11");
    }

    // The list item is cut from the public posting, so the salary is hidden in one place only.
    @Test
    void theSummaryIsBuiltFromThePublicView() {
        PublicJobPostingView shown = PublicJobPostingView.of(JobPostingRowFixtures.row(true));
        PublicJobPostingView hidden = PublicJobPostingView.of(JobPostingRowFixtures.row(false));

        assertThat(PublicJobPostingSummary.of(shown)).isEqualTo(new PublicJobPostingSummary(shown.id(),
                shown.title(), shown.positionName(), shown.departmentName(), shown.workLocationName(),
                shown.employmentTypeName(), shown.applicationDeadline(), 20_000_000L, 22_000_000L));
        PublicJobPostingSummary hiddenSummary = PublicJobPostingSummary.of(hidden);
        assertThat(hiddenSummary.salaryMin()).isNull();
        assertThat(hiddenSummary.salaryMax()).isNull();
        assertThat(fieldNames(mapper.valueToTree(hiddenSummary))).containsExactly("id", "title", "positionName",
                "departmentName", "workLocationName", "employmentTypeName", "applicationDeadline");
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
