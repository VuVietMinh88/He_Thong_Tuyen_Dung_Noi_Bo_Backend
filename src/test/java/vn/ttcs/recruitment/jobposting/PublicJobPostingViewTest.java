package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Task 309: the one builder of what candidates see, used by the preview and by the public portal. A hidden salary is
// dropped on the server, and the public posting carries no editor data. Rows come from JobPostingRowFixtures only.
// Task 316 (story S4-01): "Giới hạn dữ liệu trả ra trên cổng công khai". Every record the portal answers with is pinned
// component by component, so a new field reaches candidates only through a change of these lists.
// Task 320 (story S4-02): the public detail is the public posting, component by component and in the same order, plus
// whether candidates may still apply and when applications close.
class PublicJobPostingViewTest {
    private static final List<String> PUBLIC_FIELDS = List.of("id", "title", "positionName", "departmentName",
            "headcount", "workLocationName", "employmentTypeName", "applicationDeadline", "salaryMin", "salaryMax",
            "jobDescription", "candidateRequirements", "benefits");
    // Task 316: one posting of the public list: no texts and no headcount, which only the detail has.
    private static final List<String> SUMMARY_FIELDS = List.of("id", "title", "positionName", "departmentName",
            "workLocationName", "employmentTypeName", "applicationDeadline", "salaryMin", "salaryMax");
    // Task 320: the public detail.
    private static final List<String> DETAIL_FIELDS = List.of("id", "title", "positionName", "departmentName",
            "headcount", "workLocationName", "employmentTypeName", "applicationDeadline", "salaryMin", "salaryMax",
            "jobDescription", "candidateRequirements", "benefits", "applicationOpen", "applicationClosesAt");
    // 00:00 on 1 Dec 2026 in Vietnam: applications to a posting whose deadline is 30 Nov close then.
    private static final Instant CLOSES_AT = Instant.parse("2026-11-30T17:00:00Z");

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

    // Task 316: the page, the filter values and each value hold exactly these components, typed with the public records
    // only, so neither a JobPostingRow nor an internal view can be put into an answer of the portal.
    @Test
    void everyPortalRecordHasOnlyItsPublicComponents() {
        assertThat(components(PublicJobPostingView.class)).containsExactlyElementsOf(PUBLIC_FIELDS);
        assertThat(components(PublicJobPostingSummary.class)).containsExactlyElementsOf(SUMMARY_FIELDS);
        assertThat(components(PublicJobPostingPage.class))
                .containsExactly("items", "page", "size", "totalElements", "totalPages");
        assertThat(components(PublicJobPostingFilters.class))
                .containsExactly("departments", "workLocations", "employmentTypes");
        assertThat(components(PublicJobPostingFilterOption.class)).containsExactly("id", "name");
        assertThat(components(PublicJobPostingDetail.class)).containsExactlyElementsOf(DETAIL_FIELDS);

        assertThat(types(PublicJobPostingPage.class)).containsExactly(
                "java.util.List<vn.ttcs.recruitment.jobposting.PublicJobPostingSummary>", "int", "int", "long", "long");
        String options = "java.util.List<vn.ttcs.recruitment.jobposting.PublicJobPostingFilterOption>";
        assertThat(types(PublicJobPostingFilters.class)).containsExactly(options, options, options);
        assertThat(types(PublicJobPostingFilterOption.class)).containsExactly("java.util.UUID", "java.lang.String");
        // Task 320: the detail's first thirteen components are the public posting's, with the same types.
        List<String> viewTypes = types(PublicJobPostingView.class);
        List<String> detailTypes = types(PublicJobPostingDetail.class);
        assertThat(detailTypes.subList(0, viewTypes.size())).containsExactlyElementsOf(viewTypes);
        assertThat(detailTypes.subList(viewTypes.size(), detailTypes.size()))
                .containsExactly("boolean", "java.time.Instant");
    }

    // Task 320: the detail copies every value of the public posting, and adds the application state it is given.
    @Test
    void theDetailIsThePublicViewWithTheApplicationState() {
        for (boolean showSalary : List.of(true, false)) {
            PublicJobPostingView view = PublicJobPostingView.of(JobPostingRowFixtures.row(showSalary));
            for (boolean open : List.of(true, false)) {
                PublicJobPostingDetail detail = PublicJobPostingDetail.of(view, open, CLOSES_AT);

                assertThat(detail).isEqualTo(new PublicJobPostingDetail(view.id(), view.title(), view.positionName(),
                        view.departmentName(), view.headcount(), view.workLocationName(), view.employmentTypeName(),
                        view.applicationDeadline(), view.salaryMin(), view.salaryMax(), view.jobDescription(),
                        view.candidateRequirements(), view.benefits(), open, CLOSES_AT));
                RecordComponent[] viewComponents = PublicJobPostingView.class.getRecordComponents();
                RecordComponent[] detailComponents = PublicJobPostingDetail.class.getRecordComponents();
                for (int index = 0; index < viewComponents.length; index++) {
                    assertThat(value(detailComponents[index], detail)).as(viewComponents[index].getName())
                            .isEqualTo(value(viewComponents[index], view));
                }
            }
        }
    }

    // Task 320: the JSON of the detail is the JSON of the public posting followed by the two state keys; a hidden
    // salary leaves its keys out here too, and the closing moment is an ISO-8601 instant in UTC.
    @Test
    void theDetailJsonIsThePublicPostingFollowedByTheApplicationState() {
        for (boolean showSalary : List.of(true, false)) {
            PublicJobPostingView view = PublicJobPostingView.of(JobPostingRowFixtures.row(showSalary, null));
            JsonNode posting = mapper.valueToTree(view);
            JsonNode detail = mapper.valueToTree(PublicJobPostingDetail.of(view, false, CLOSES_AT));

            List<String> expected = new ArrayList<>(fieldNames(posting));
            expected.addAll(List.of("applicationOpen", "applicationClosesAt"));
            assertThat(fieldNames(detail)).containsExactlyElementsOf(expected);
            for (String key : fieldNames(posting)) {
                assertThat(detail.path(key)).as(key).isEqualTo(posting.path(key));
            }
            assertThat(detail.path("applicationOpen").isBoolean()).isTrue();
            assertThat(detail.path("applicationOpen").asBoolean()).isFalse();
            assertThat(detail.path("applicationClosesAt").asText()).isEqualTo("2026-11-30T17:00:00Z");
            assertThat(detail.path("benefits").isNull()).isTrue();
            if (!showSalary) {
                assertThat(detail.toString()).doesNotContain("salary", "20000000", "22000000");
            }
        }
        JsonNode open = mapper.valueToTree(PublicJobPostingDetail.of(PublicJobPostingView.of(
                JobPostingRowFixtures.row(true)), true, CLOSES_AT));
        assertThat(fieldNames(open)).containsExactlyElementsOf(DETAIL_FIELDS);
        assertThat(open.path("applicationOpen").asBoolean()).isTrue();
        // Like the public posting, no editor data: no status (the state is a boolean, not a status name), revision,
        // requisition, creator or reviewer.
        assertThat(open.toString()).doesNotContain(JobPostingRowFixtures.REQUISITION.toString(),
                JobPostingRowFixtures.CREATOR.toString(), JobPostingRowFixtures.REVIEWER.toString(), "\"status\"",
                "PUBLISHED", "revision", "2026-10-11");
    }

    // Task 316: a list item has the salary keys only when the posting shows the salary, and never the texts, the
    // headcount, the ids of the requisition, the position, the department and the catalog values, the editors or the
    // review.
    @Test
    void theSummaryJsonCarriesOnlyListFields() {
        JsonNode shown = mapper.valueToTree(PublicJobPostingSummary.of(PublicJobPostingView.of(
                JobPostingRowFixtures.row(true))));
        JsonNode hidden = mapper.valueToTree(PublicJobPostingSummary.of(PublicJobPostingView.of(
                JobPostingRowFixtures.row(false))));

        assertThat(fieldNames(shown)).containsExactlyElementsOf(SUMMARY_FIELDS);
        assertThat(shown.path("salaryMin").asLong()).isEqualTo(20_000_000L);
        assertThat(shown.path("salaryMax").asLong()).isEqualTo(22_000_000L);
        List<String> withoutSalary = new ArrayList<>(SUMMARY_FIELDS);
        withoutSalary.removeAll(List.of("salaryMin", "salaryMax"));
        assertThat(fieldNames(hidden)).containsExactlyElementsOf(withoutSalary);
        assertThat(hidden.toString()).doesNotContain("20000000", "22000000", "salary");
        for (JsonNode summary : List.of(shown, hidden)) {
            assertThat(summary.path("id").asText()).isEqualTo(JobPostingRowFixtures.ID.toString());
            assertThat(summary.toString()).doesNotContain(JobPostingRowFixtures.REQUISITION.toString(),
                    JobPostingRowFixtures.POSITION.toString(), JobPostingRowFixtures.DEPARTMENT.toString(),
                    JobPostingRowFixtures.WORK_LOCATION.toString(), JobPostingRowFixtures.EMPLOYMENT_TYPE.toString(),
                    JobPostingRowFixtures.CREATOR.toString(), JobPostingRowFixtures.REVIEWER.toString(),
                    "Trần Văn Tuyển", "Nguyễn Thị Hoa", "Đồng ý đăng tin.", "Phát triển API tuyển dụng",
                    "Tối thiểu 2 năm", "Bảo hiểm đầy đủ.", "headcount", "PUBLISHED", "APPROVED", "2026-10-11");
        }
    }

    // Task 316: the full posting names the position, the department and the catalog values, never their ids.
    @Test
    void thePublicPostingNamesButNeverIdentifiesItsOrganizationValues() {
        JsonNode json = mapper.valueToTree(PublicJobPostingView.of(JobPostingRowFixtures.row(true)));

        assertThat(json.path("positionName").asText()).isEqualTo("Lập trình viên");
        assertThat(json.path("departmentName").asText()).isEqualTo("Phòng IT");
        assertThat(json.path("workLocationName").asText()).isEqualTo("Hà Nội");
        assertThat(json.path("employmentTypeName").asText()).isEqualTo("Toàn thời gian");
        assertThat(json.toString()).doesNotContain(JobPostingRowFixtures.POSITION.toString(),
                JobPostingRowFixtures.DEPARTMENT.toString(), JobPostingRowFixtures.WORK_LOCATION.toString(),
                JobPostingRowFixtures.EMPLOYMENT_TYPE.toString());
    }

    private static Object value(RecordComponent component, Record record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static List<String> types(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(component -> component.getGenericType().getTypeName())
                .toList();
    }
}
