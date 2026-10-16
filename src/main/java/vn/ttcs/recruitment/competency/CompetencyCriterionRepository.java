package vn.ttcs.recruitment.competency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompetencyCriterionRepository extends JpaRepository<CompetencyCriterion, UUID> {

    List<CompetencyCriterion> findByFrameworkIdOrderBySortOrderAsc(UUID frameworkId);

    // Only the framework id of one criterion (Jira 221). It does not load the criterion entity, so a read after
    // locking the framework (findByIdForUpdate) still reads the criterion row from the database instead of an older
    // copy.
    @Query("select c.frameworkId from CompetencyCriterion c where c.id = :id")
    Optional<UUID> findFrameworkIdById(@Param("id") UUID id);

    // SELECT ... FOR UPDATE on one criterion (Jira 222). InterviewQuestionService takes it after the framework lock,
    // so two question writes on the same criterion run one after the other and the second one sees the question the
    // first one saved. Question writes on other criteria do not wait. Empty when the criterion has been deleted.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CompetencyCriterion c where c.id = :id")
    Optional<CompetencyCriterion> findByIdForUpdate(@Param("id") UUID id);

    // Number of criteria of each listed framework, in one query instead of one query per framework.
    // A framework without criteria has no row in the result.
    @Query("""
            select c.frameworkId as frameworkId, count(c) as criterionCount
            from CompetencyCriterion c
            where c.frameworkId in :frameworkIds
            group by c.frameworkId
            """)
    List<CriterionCount> countByFrameworkIds(@Param("frameworkIds") Collection<UUID> frameworkIds);

    interface CriterionCount {
        UUID getFrameworkId();
        long getCriterionCount();
    }

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
