package vn.ttcs.recruitment.evaluationreport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.ttcs.recruitment.evaluation.EvaluationView;

/**
 * Pure aggregation of one application's evaluation forms. Only forms whose status is exactly SUBMITTED count;
 * drafts (including reopened ones) are ignored. Authorization and form selection are the caller's concern, and
 * comments and evaluator identity never leave this class.
 */
public final class EvaluationAggregation {

    static final String SUBMITTED = "SUBMITTED";
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private static final Comparator<DefinitionKey> DEFINITION_ORDER = Comparator
            .comparing(DefinitionKey::frameworkId)
            .thenComparing(DefinitionKey::criterionId)
            .thenComparing(DefinitionKey::name)
            .thenComparing(DefinitionKey::description, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(DefinitionKey::weight);

    private EvaluationAggregation() {
    }

    public record CriterionAverage(UUID frameworkId, UUID criterionId, String name, String description,
                                   BigDecimal weight, BigDecimal averageScore, int submittedCount) { }

    public record Result(int submittedCount, BigDecimal averageTotal, List<CriterionAverage> criteria) {
        public Result {
            criteria = List.copyOf(criteria);
        }
    }

    /** A criterion snapshot is identified by its whole definition, so reworded or reweighted criteria stay apart. */
    private record DefinitionKey(UUID frameworkId, UUID criterionId, String name, String description,
                                 BigDecimal weight) { }

    private static final class ScoreSum {
        private long sum;
        private int count;

        void add(int score) {
            sum += score;
            count++;
        }
    }

    public static Result aggregate(List<EvaluationView> forms) {
        Objects.requireNonNull(forms, "forms must not be null");
        List<EvaluationView> submitted = selectSubmitted(forms);
        if (submitted.isEmpty()) {
            return new Result(0, null, List.of());
        }

        BigDecimal totalSum = BigDecimal.ZERO;
        Map<DefinitionKey, ScoreSum> groups = new HashMap<>();
        for (EvaluationView form : submitted) {
            if (form.totalScore() == null) {
                throw corrupt(form, "totalScore is missing");
            }
            totalSum = totalSum.add(form.totalScore());
            if (form.framework() == null || form.framework().id() == null) {
                throw corrupt(form, "framework snapshot is missing");
            }
            if (form.items() == null) {
                throw corrupt(form, "items are missing");
            }
            UUID frameworkId = form.framework().id();
            Set<UUID> criterionIds = new HashSet<>();
            for (EvaluationView.Item item : form.items()) {
                DefinitionKey key = definitionOf(form, frameworkId, item);
                if (!criterionIds.add(key.criterionId())) {
                    throw corrupt(form, "criterion " + key.criterionId() + " appears more than once");
                }
                groups.computeIfAbsent(key, k -> new ScoreSum()).add(item.score());
            }
        }

        List<CriterionAverage> criteria = groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(DEFINITION_ORDER))
                .map(entry -> {
                    DefinitionKey key = entry.getKey();
                    ScoreSum scores = entry.getValue();
                    return new CriterionAverage(key.frameworkId(), key.criterionId(), key.name(), key.description(),
                            key.weight(), mean(BigDecimal.valueOf(scores.sum), scores.count), scores.count);
                })
                .toList();
        return new Result(submitted.size(), mean(totalSum, submitted.size()), criteria);
    }

    private static List<EvaluationView> selectSubmitted(List<EvaluationView> forms) {
        Set<UUID> formIds = new HashSet<>();
        UUID applicationId = null;
        List<EvaluationView> submitted = new ArrayList<>();
        int index = 0;
        for (EvaluationView form : forms) {
            if (form == null) {
                throw new IllegalArgumentException("forms[" + index + "] is null");
            }
            if (form.id() == null) {
                throw new IllegalArgumentException("forms[" + index + "] has no id");
            }
            if (!formIds.add(form.id())) {
                throw new IllegalArgumentException("Duplicate evaluation form " + form.id());
            }
            if (form.applicationId() == null) {
                throw corrupt(form, "applicationId is missing");
            }
            if (applicationId == null) {
                applicationId = form.applicationId();
            } else if (!applicationId.equals(form.applicationId())) {
                throw new IllegalArgumentException("Evaluation forms belong to different applications: "
                        + applicationId + " and " + form.applicationId());
            }
            if (form.status() == null) {
                throw corrupt(form, "status is missing");
            }
            if (SUBMITTED.equals(form.status())) {
                submitted.add(form);
            }
            index++;
        }
        return submitted;
    }

    private static DefinitionKey definitionOf(EvaluationView form, UUID frameworkId, EvaluationView.Item item) {
        if (item == null) {
            throw corrupt(form, "contains a null item");
        }
        if (item.criterionId() == null) {
            throw corrupt(form, "item has no criterionId");
        }
        if (item.name() == null) {
            throw corrupt(form, "criterion " + item.criterionId() + " has no name");
        }
        if (item.weight() == null) {
            throw corrupt(form, "criterion " + item.criterionId() + " has no weight");
        }
        if (item.score() == null) {
            throw corrupt(form, "criterion " + item.criterionId() + " has no score");
        }
        return new DefinitionKey(frameworkId, item.criterionId(), item.name(), item.description(),
                normalizeWeight(item.weight()));
    }

    /** Numerically equal weights share one representation (40, 40.0, 4E+1 -> 40.00) without losing precision. */
    static BigDecimal normalizeWeight(BigDecimal weight) {
        BigDecimal stripped = weight.stripTrailingZeros();
        return stripped.scale() < SCALE ? stripped.setScale(SCALE) : stripped;
    }

    private static BigDecimal mean(BigDecimal sum, int count) {
        return sum.divide(BigDecimal.valueOf(count), SCALE, ROUNDING);
    }

    private static IllegalArgumentException corrupt(EvaluationView form, String reason) {
        return new IllegalArgumentException("Evaluation form " + form.id() + " is corrupt: " + reason);
    }
}
