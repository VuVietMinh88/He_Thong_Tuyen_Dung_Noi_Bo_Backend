package vn.ttcs.recruitment.competency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CompetencyCriterionRepository extends JpaRepository<CompetencyCriterion, UUID> {

    List<CompetencyCriterion> findByFrameworkIdOrderBySortOrderAsc(UUID frameworkId);

    // V8 checks the unique name and sort order of criteria only at COMMIT. COMMIT runs after the service method
    // has returned, so a duplicate found there cannot be turned into a 409 and would end as a 500.
    // Call this as the last step of a criteria write, inside the same transaction: it flushes pending changes and
    // checks both constraints at once, throwing DataIntegrityViolationException with the constraint name when a
    // duplicate is left. If another unfinished transaction wrote the same name or sort order, it first waits for
    // that transaction to end. MANDATORY: outside a transaction there is nothing to check, so Spring refuses it.
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true)
    @Query(value = "SET CONSTRAINTS competency_criteria_framework_name_key, "
            + "competency_criteria_framework_sort_order_key IMMEDIATE", nativeQuery = true)
    void checkUniqueConstraintsNow();
}
