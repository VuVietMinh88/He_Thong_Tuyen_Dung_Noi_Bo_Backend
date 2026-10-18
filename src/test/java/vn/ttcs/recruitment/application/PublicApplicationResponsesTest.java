package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.application.PublicResponseContract.assertExactComponents;
import static vn.ttcs.recruitment.application.PublicResponseContract.assertJsonKeys;
import static vn.ttcs.recruitment.application.PublicResponseContract.assertNoInternalComponents;

/**
 * Task 341 (story S4-05, "Không tiết lộ nhận xét nội bộ hay tên người phỏng vấn"): the records a candidate receives are
 * whitelists. The lookup's view has exactly its four public fields; the intake's receipt carries nothing but the
 * candidate's own receipt. The last tests check PublicResponseContract itself, since the guarantee is only as good as
 * the check that pins it.
 */
class PublicApplicationResponsesTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-15T03:00:00Z");

    @Test
    void theLookupViewIsExactlyItsFourPublicFields() {
        assertExactComponents(PublicApplicationView.class, "postingTitle", "receivedAt", "status", "statusName");
        assertNoInternalComponents(PublicApplicationView.class);
    }

    // applicationId, trackingCode and trackingUrl are the candidate's own receipt, returned once to the person who
    // submitted (task 326, 329); nothing else of the intake's 201 may look internal.
    @Test
    void theIntakeReceiptCarriesOnlyTheCandidatesOwnReceipt() {
        assertExactComponents(ApplicationIntakeController.AcceptedApplication.class,
                "applicationId", "status", "statusName", "receivedAt", "trackingCode", "trackingUrl");
        assertNoInternalComponents(ApplicationIntakeController.AcceptedApplication.class,
                "applicationId", "trackingCode", "trackingUrl");
    }

    // The view is built from the stage but never carries it: only task 340's public code and label.
    @ParameterizedTest
    @EnumSource(ApplicationStage.class)
    void theViewCarriesThePublicStatusNeverTheStage(ApplicationStage stage) {
        var view = PublicApplicationView.of("Lập trình viên Java", RECEIVED_AT, stage);

        assertThat(view).isEqualTo(new PublicApplicationView("Lập trình viên Java", RECEIVED_AT,
                PublicApplicationStatus.of(stage).name(), PublicApplicationStatus.of(stage).displayName()));
        assertThat(List.of(view.status(), view.statusName())).doesNotContain(stage.name(), stage.displayName());
    }

    @Test
    void theViewSerializesToExactlyItsFourKeys() {
        var json = JsonMapper.builder().build();
        var node = json.readTree(json.writeValueAsString(
                PublicApplicationView.of("Lập trình viên Java", RECEIVED_AT, ApplicationStage.OFFER)));

        assertJsonKeys(node, "postingTitle", "receivedAt", "status", "statusName");
        assertThat(node.get("status").asString()).isEqualTo("INTERVIEWING");
        assertThat(node.get("statusName").asString()).isEqualTo("Mời phỏng vấn");
    }

    // What a Sprint 5 or 6 change could add by mistake: each one is refused.
    private record WithNote(String postingTitle, String internalNote) {}
    private record WithStage(String status, ApplicationStage current) {}
    private record WithStageList(String status, List<ApplicationStage> history) {}
    private record Interview(Instant at, String interviewerName) {}
    private record WithNestedInterview(String status, Optional<Interview> next) {}
    private record Entry(String status, Instant occurredAt, String comment) {}
    private record WithTimelineNotes(String status, List<Entry> timeline) {}
    private record WithEntryArray(String status, Entry[] timeline) {}
    private record WithMappedEntries(String status, Map<String, Entry> byStatus) {}
    private record WithRequisition(String status, String requisitionId) {}
    private record WithIdentifier(String status, UUID ref) {}
    private record WithEvaluation(String status, Integer score) {}
    private record WithRecruiter(String status, String assignedRecruiter) {}

    @Test
    void theContractRefusesEveryInternalLookingComponent() {
        for (var type : List.<Class<? extends Record>>of(WithNote.class, WithStage.class, WithStageList.class,
                WithNestedInterview.class, WithTimelineNotes.class, WithEntryArray.class, WithMappedEntries.class,
                WithRequisition.class, WithIdentifier.class, WithEvaluation.class, WithRecruiter.class)) {
            assertThatThrownBy(() -> assertNoInternalComponents(type)).as(type.getSimpleName())
                    .isInstanceOf(AssertionError.class);
        }
        // A nested component is named by its path.
        assertThatThrownBy(() -> assertNoInternalComponents(WithNestedInterview.class))
                .hasMessageContaining("next.interviewerName");
        assertThatThrownBy(() -> assertNoInternalComponents(WithTimelineNotes.class))
                .hasMessageContaining("timeline.comment");
    }

    // The timeline that S5-07 may add: public statuses and times only.
    private record PublicEntry(String status, String statusName, Instant occurredAt) {}
    private record WithPublicTimeline(String postingTitle, Instant receivedAt, String status, String statusName,
                                      List<PublicEntry> timeline) {}

    @Test
    void aTimelineOfPublicStatusesAndTimesIsAccepted() {
        assertNoInternalComponents(WithPublicTimeline.class);
    }

    // An exception must name a component that would otherwise be refused, so a stale exception fails.
    @Test
    void anAllowedExceptionMustNameAnInternalLookingComponent() {
        assertNoInternalComponents(WithRequisition.class, "requisitionId");
        assertThatThrownBy(() -> assertNoInternalComponents(PublicApplicationView.class, "postingTitle"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("postingTitle");
        assertThatThrownBy(() -> assertNoInternalComponents(PublicApplicationView.class, "applicationId"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("applicationId");
        assertThatThrownBy(() -> assertNoInternalComponents(WithNestedInterview.class, "interviewerName"))
                .isInstanceOf(AssertionError.class);
        assertNoInternalComponents(WithNestedInterview.class, "next.interviewerName");
    }

    @Test
    void theJsonKeysMustMatchExactly() {
        var json = JsonMapper.builder().build();
        var node = json.readTree("{\"status\":\"RECEIVED\",\"statusName\":\"Đã nhận\"}");

        assertJsonKeys(node, "statusName", "status");
        assertThatThrownBy(() -> assertJsonKeys(node, "status")).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertJsonKeys(node, "status", "statusName", "postingTitle"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertJsonKeys(json.readTree("[]"))).isInstanceOf(AssertionError.class);
    }
}
