package vn.ttcs.recruitment.interviewquestion;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, UUID> {

    // Every question of one criterion (active or not), oldest first; the id keeps the order stable when two
    // questions were created at the same moment.
    List<InterviewQuestion> findByCriterionIdOrderByCreatedAtAscIdAsc(UUID criterionId);

    // SELECT ... FOR UPDATE (Jira 221): two edits of the same question run one after the other, and the second one
    // starts from what the first one saved.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from InterviewQuestion q where q.id = :id")
    Optional<InterviewQuestion> findByIdForUpdate(@Param("id") UUID id);
}
