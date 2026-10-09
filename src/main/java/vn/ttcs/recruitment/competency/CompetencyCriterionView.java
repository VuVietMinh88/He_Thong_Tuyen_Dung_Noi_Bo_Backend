package vn.ttcs.recruitment.competency;

import java.math.BigDecimal;
import java.util.UUID;

// weight always has two decimals, e.g. 40.00, as NUMERIC(5,2) stores it.
public record CompetencyCriterionView(UUID id, String name, String description, BigDecimal weight, int sortOrder) {

    static CompetencyCriterionView from(CompetencyCriterion criterion) {
        return new CompetencyCriterionView(criterion.getId(), criterion.getName(), criterion.getDescription(),
                criterion.getWeight(), criterion.getSortOrder());
    }
}
