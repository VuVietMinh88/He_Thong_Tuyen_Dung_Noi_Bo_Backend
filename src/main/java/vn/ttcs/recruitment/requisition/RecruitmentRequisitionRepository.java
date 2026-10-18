package vn.ttcs.recruitment.requisition;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RecruitmentRequisitionRepository extends JpaRepository<RecruitmentRequisition, UUID> {

    // Holds the row until commit, so two edits of the same draft run one after the other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecruitmentRequisition r where r.id = :id")
    Optional<RecruitmentRequisition> findByIdForUpdate(@Param("id") UUID id);

    // Feedback item 10: the requisitions of one account handover, locked like findByIdForUpdate and in id order, so
    // two handovers that share requisitions cannot deadlock. An id that does not exist locks nothing.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecruitmentRequisition r where r.id in :ids order by r.id")
    List<RecruitmentRequisition> findAllByIdForUpdate(@Param("ids") Set<UUID> ids);

    // Task 267: the requisitions waiting for approval, for the approver inbox.
    List<RecruitmentRequisition> findByStatus(RequisitionStatus status);
}
