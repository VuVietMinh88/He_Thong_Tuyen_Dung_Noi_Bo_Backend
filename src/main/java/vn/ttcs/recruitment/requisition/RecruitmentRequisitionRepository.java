package vn.ttcs.recruitment.requisition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecruitmentRequisitionRepository extends JpaRepository<RecruitmentRequisition, UUID> {
}
