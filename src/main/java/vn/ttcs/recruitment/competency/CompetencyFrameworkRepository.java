package vn.ttcs.recruitment.competency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompetencyFrameworkRepository extends JpaRepository<CompetencyFramework, UUID> {
}
