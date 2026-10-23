package vn.ttcs.recruitment.evaluationreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.evaluation.EvaluationConclusion;
import vn.ttcs.recruitment.evaluation.EvaluationView;
import vn.ttcs.recruitment.evaluation.EvaluationView.Item;
import vn.ttcs.recruitment.evaluationreport.EvaluationAggregation.CriterionAverage;
import vn.ttcs.recruitment.evaluationreport.EvaluationAggregation.Result;

class EvaluationAggregationTest {

    private static final UUID APP = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_APP = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID INTERVIEW = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID FW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FW2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C1 = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID C2 = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    @Test
    void ignoresDraftsIncludingReopenedPreviouslySubmittedForms() {
        Result result = EvaluationAggregation.aggregate(List.of(
                submitted("80", item(C1, "Communication", null, "40", 4)),
                form(UUID.randomUUID(), APP, FW, "DRAFT", null, null, List.of(item(C1, "Communication", null, "40", null))),
                form(UUID.randomUUID(), APP, FW, "DRAFT", new BigDecimal("10"), NOW,
                        List.of(item(C1, "Communication", null, "40", 1)))));

        assertThat(result.submittedCount()).isEqualTo(1);
        assertThat(result.averageTotal()).isEqualTo(new BigDecimal("80.00"));
        assertThat(result.criteria()).singleElement().satisfies(c -> {
            assertThat(c.averageScore()).isEqualTo(new BigDecimal("4.00"));
            assertThat(c.submittedCount()).isEqualTo(1);
        });
    }

    @Test
    void emptyListAndDraftOnlyListProduceEmptyResult() {
        Result empty = new Result(0, null, List.of());

        assertThat(EvaluationAggregation.aggregate(List.of())).isEqualTo(empty);
        assertThat(EvaluationAggregation.aggregate(List.of(
                form(UUID.randomUUID(), APP, FW, "DRAFT", null, null, List.of())))).isEqualTo(empty);
    }

    @Test
    void rejectsNullArgumentAndNullElement() {
        assertThatThrownBy(() -> EvaluationAggregation.aggregate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("forms");
        assertThatThrownBy(() -> EvaluationAggregation.aggregate(Arrays.asList(submitted("80"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forms[1]");
    }

    @Test
    void roundsHalfUpRatherThanHalfEven() {
        Result result = EvaluationAggregation.aggregate(List.of(
                submitted("70.00", item(C1, "Communication", null, "40", 1)),
                submitted("70.01", item(C1, "Communication", null, "40", 2))));

        assertThat(result.averageTotal()).isEqualTo(new BigDecimal("70.01"));
        assertThat(result.criteria().get(0).averageScore()).isEqualTo(new BigDecimal("1.50"));
    }

    @Test
    void roundsNonTerminatingAveragesToTwoDecimals() {
        Result result = EvaluationAggregation.aggregate(List.of(
                submitted("80", item(C1, "Communication", null, "40", 1), item(C2, "Coding", null, "60", 2)),
                submitted("81", item(C1, "Communication", null, "40", 2), item(C2, "Coding", null, "60", 2)),
                submitted("81", item(C1, "Communication", null, "40", 2), item(C2, "Coding", null, "60", 3))));

        assertThat(result.averageTotal()).isEqualTo(new BigDecimal("80.67"));
        assertThat(result.criteria()).extracting(CriterionAverage::averageScore)
                .containsExactly(new BigDecimal("1.67"), new BigDecimal("2.33"));
    }

    @Test
    void changedDefinitionsStayInSeparateGroups() {
        Result result = EvaluationAggregation.aggregate(List.of(
                submitted("50", item(C1, "Communication", "old wording", "40", 1)),
                submitted("50", item(C1, "Communication", "new wording", "40", 2)),
                submitted("50", item(C1, "Communication skills", "old wording", "40", 3)),
                submitted("50", item(C1, "Communication", "old wording", "50", 4)),
                form(UUID.randomUUID(), APP, FW2, "SUBMITTED", new BigDecimal("50"), NOW,
                        List.of(item(C1, "Communication", "old wording", "40", 5)))));

        assertThat(result.criteria()).hasSize(5).allSatisfy(c -> assertThat(c.submittedCount()).isEqualTo(1));
        assertThat(result.criteria())
                .extracting(CriterionAverage::frameworkId, CriterionAverage::name, CriterionAverage::description,
                        CriterionAverage::weight, CriterionAverage::averageScore)
                .containsExactly(
                        tuple(FW, "Communication", "new wording", new BigDecimal("40.00"), new BigDecimal("2.00")),
                        tuple(FW, "Communication", "old wording", new BigDecimal("40.00"), new BigDecimal("1.00")),
                        tuple(FW, "Communication", "old wording", new BigDecimal("50.00"), new BigDecimal("4.00")),
                        tuple(FW, "Communication skills", "old wording", new BigDecimal("40.00"), new BigDecimal("3.00")),
                        tuple(FW2, "Communication", "old wording", new BigDecimal("40.00"), new BigDecimal("5.00")));
    }

    @Test
    void nullDescriptionSortsFirst() {
        Result result = EvaluationAggregation.aggregate(List.of(
                submitted("50", item(C1, "Communication", "described", "40", 1)),
                submitted("50", item(C1, "Communication", null, "40", 2))));

        assertThat(result.criteria()).extracting(CriterionAverage::description).containsExactly(null, "described");
    }

    @Test
    void numericallyEqualWeightsShareOneNormalizedGroup() {
        Result result = EvaluationAggregation.aggregate(List.of(
                submitted("50", item(C1, "Communication", null, "40", 1)),
                submitted("50", item(C1, "Communication", null, "40.0", 2)),
                submitted("50", item(C1, "Communication", null, "40.000", 3)),
                submitted("50", item(C1, "Communication", null, "4E+1", 4))));

        assertThat(result.criteria()).singleElement().satisfies(c -> {
            assertThat(c.weight()).isEqualTo(new BigDecimal("40.00"));
            assertThat(c.submittedCount()).isEqualTo(4);
            assertThat(c.averageScore()).isEqualTo(new BigDecimal("2.50"));
        });
    }

    @Test
    void normalizationKeepsPrecisionBeyondTwoDecimals() {
        assertThat(EvaluationAggregation.normalizeWeight(new BigDecimal("40.1250"))).isEqualTo(new BigDecimal("40.125"));
        assertThat(EvaluationAggregation.normalizeWeight(new BigDecimal("0"))).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void absentCriterionIsNotCountedAsZero() {
        Result result = EvaluationAggregation.aggregate(List.of(
                submitted("70", item(C1, "Communication", null, "40", 4), item(C2, "Coding", null, "60", 2)),
                submitted("60", item(C1, "Communication", null, "40", 2))));

        assertThat(result.submittedCount()).isEqualTo(2);
        assertThat(result.criteria())
                .extracting(CriterionAverage::criterionId, CriterionAverage::averageScore, CriterionAverage::submittedCount)
                .containsExactly(
                        tuple(C1, new BigDecimal("3.00"), 2),
                        tuple(C2, new BigDecimal("2.00"), 1));
    }

    @Test
    void rejectsSubmittedFormWithoutTotal() {
        assertCorrupt(form(UUID.randomUUID(), APP, FW, "SUBMITTED", null, NOW,
                List.of(item(C1, "Communication", null, "40", 3))), "totalScore");
    }

    @Test
    void rejectsSubmittedFormWithUnscoredItem() {
        assertCorrupt(submitted("80", item(C1, "Communication", null, "40", null)), "score");
    }

    @Test
    void rejectsSubmittedFormWithMissingStructure() {
        assertCorrupt(form(UUID.randomUUID(), APP, null, "SUBMITTED", new BigDecimal("80"), NOW, List.of()),
                "framework");
        assertCorrupt(form(UUID.randomUUID(), APP, FW, "SUBMITTED", new BigDecimal("80"), NOW, null), "items");
        assertCorrupt(form(UUID.randomUUID(), APP, FW, "SUBMITTED", new BigDecimal("80"), NOW,
                Arrays.asList((Item) null)), "null item");
        assertCorrupt(submitted("80", new Item(C1, "Communication", null, null, 1, 3, null)), "weight");
    }

    @Test
    void rejectsRepeatedCriterionWithinOneForm() {
        assertCorrupt(submitted("80",
                item(C1, "Communication", null, "40", 3),
                item(C1, "Communication", null, "40", 3)), "more than once");
    }

    @Test
    void rejectsDuplicateFormIds() {
        UUID id = UUID.randomUUID();
        EvaluationView first = form(id, APP, FW, "SUBMITTED", new BigDecimal("80"), NOW,
                List.of(item(C1, "Communication", null, "40", 4)));
        EvaluationView reopenedCopy = form(id, APP, FW, "DRAFT", new BigDecimal("80"), NOW,
                List.of(item(C1, "Communication", null, "40", 4)));

        assertThatThrownBy(() -> EvaluationAggregation.aggregate(List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> EvaluationAggregation.aggregate(List.of(first, reopenedCopy)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void acceptsManyFormsOfSameApplicationButRejectsMixedApplications() {
        assertThat(EvaluationAggregation.aggregate(List.of(submitted("80"), submitted("90"))).submittedCount())
                .isEqualTo(2);

        EvaluationView foreignDraft = form(UUID.randomUUID(), OTHER_APP, FW, "DRAFT", null, null, List.of());
        assertThatThrownBy(() -> EvaluationAggregation.aggregate(List.of(submitted("80"), foreignDraft)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different applications");
    }

    @Test
    void resultIsImmutableAndIndependentOfInput() {
        List<EvaluationView> input = new ArrayList<>(List.of(
                submitted("80", item(C1, "Communication", null, "40", 4))));
        Result result = EvaluationAggregation.aggregate(input);

        input.add(submitted("10", item(C1, "Communication", null, "40", 1)));

        assertThat(result.submittedCount()).isEqualTo(1);
        assertThat(result.criteria()).hasSize(1);
        assertThatThrownBy(() -> result.criteria().add(result.criteria().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);

        List<CriterionAverage> criteria = new ArrayList<>(result.criteria());
        Result copied = new Result(1, result.averageTotal(), criteria);
        criteria.clear();
        assertThat(copied.criteria()).hasSize(1);
    }

    @Test
    void resultIsDeterministicRegardlessOfInputOrder() {
        EvaluationView a = submitted("70", item(C2, "Coding", null, "60", 2), item(C1, "Communication", null, "40", 4));
        EvaluationView b = submitted("75", item(C1, "Communication", "v2", "40", 3));
        EvaluationView c = form(UUID.randomUUID(), APP, FW2, "SUBMITTED", new BigDecimal("90"), NOW,
                List.of(item(C1, "Communication", null, "40", 5)));

        Result forward = EvaluationAggregation.aggregate(List.of(a, b, c));
        Result backward = EvaluationAggregation.aggregate(List.of(c, b, a));

        assertThat(backward).isEqualTo(forward);
        assertThat(forward.criteria())
                .extracting(CriterionAverage::frameworkId, CriterionAverage::criterionId, CriterionAverage::description)
                .containsExactly(
                        tuple(FW, C1, null),
                        tuple(FW, C1, "v2"),
                        tuple(FW, C2, null),
                        tuple(FW2, C1, null));
    }

    private static void assertCorrupt(EvaluationView form, String reason) {
        assertThatThrownBy(() -> EvaluationAggregation.aggregate(List.of(form)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(form.id().toString())
                .hasMessageContaining(reason);
    }

    private static EvaluationView submitted(String total, Item... items) {
        return form(UUID.randomUUID(), APP, FW, "SUBMITTED", new BigDecimal(total), NOW, List.of(items));
    }

    private static EvaluationView form(UUID id, UUID applicationId, UUID frameworkId, String status,
                                       BigDecimal total, Instant firstSubmittedAt, List<Item> items) {
        EvaluationView.Framework framework = frameworkId == null
                ? null
                : new EvaluationView.Framework(frameworkId, "FW", "Framework");
        return new EvaluationView(id, INTERVIEW, applicationId, UUID.randomUUID(), framework, status, 1L, items,
                EvaluationConclusion.HIRE, total, firstSubmittedAt,
                "SUBMITTED".equals(status) ? NOW : null, NOW, NOW);
    }

    private static Item item(UUID criterionId, String name, String description, String weight, Integer score) {
        return new Item(criterionId, name, description, new BigDecimal(weight), 1, score, "private comment");
    }
}
