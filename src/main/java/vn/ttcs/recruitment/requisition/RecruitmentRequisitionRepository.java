package vn.ttcs.recruitment.requisition;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface RecruitmentRequisitionRepository extends JpaRepository<RecruitmentRequisition, UUID> {

    // Holds the row until commit, so two edits of the same draft run one after the other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecruitmentRequisition r where r.id = :id")
    Optional<RecruitmentRequisition> findByIdForUpdate(@Param("id") UUID id);

    // Callers with REQUISITIONS_READ_ALL: every requisition whose status is in the list.
    Page<RecruitmentRequisition> findByStatusIn(Collection<RequisitionStatus> statuses, Pageable pageable);

    // Callers with REQUISITIONS_READ_SCOPED: only requisitions of these departments.
    // The service never passes an empty departmentIds collection.
    Page<RecruitmentRequisition> findByStatusInAndDepartmentIdIn(Collection<RequisitionStatus> statuses,
                                                                Collection<UUID> departmentIds, Pageable pageable);
}
