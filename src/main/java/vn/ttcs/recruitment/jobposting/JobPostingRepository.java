package vn.ttcs.recruitment.jobposting;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Task 302: job postings (V21). Their authors (job_posting_authors) are not mapped here.
public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {

    // Task 303: holds the row until commit, so two saves of the same posting run one after the other. Every writer
    // locks the posting's requisition first (JobPostingSourceRepository.lockSource), never the other way round.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from JobPosting p where p.id = :id")
    Optional<JobPosting> findByIdForUpdate(@Param("id") UUID id);

    // Task 303: a requisition has at most one posting (job_postings_requisition_id_key). The caller holds the
    // requisition row lock, so a second create of the same requisition waits and then finds the first one.
    boolean existsByRequisitionId(UUID requisitionId);

    // Task 303: the requisition of a posting, which never changes, read without loading or locking the posting, so a
    // writer can lock the requisition before the posting.
    @Query("select p.requisitionId from JobPosting p where p.id = :id")
    Optional<UUID> findRequisitionIdById(@Param("id") UUID id);

    // Task 292: the postings of a requisition, locked after the requisition (lock order of every posting writer). At
    // most one under job_postings_requisition_id_key; ordered by id so the order stays safe if that key is ever
    // relaxed.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from JobPosting p where p.requisitionId = :requisitionId order by p.id")
    List<JobPosting> findByRequisitionIdForUpdate(@Param("requisitionId") UUID requisitionId);
}
