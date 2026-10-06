package vn.ttcs.recruitment.interviewquestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, UUID> {

    // Every question of one criterion (active or not), oldest first; the id keeps the order stable when two
    // questions were created at the same moment.
    List<InterviewQuestion> findByCriterionIdOrderByCreatedAtAscIdAsc(UUID criterionId);
}
