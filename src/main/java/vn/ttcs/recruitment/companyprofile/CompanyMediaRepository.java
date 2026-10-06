package vn.ttcs.recruitment.companyprofile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompanyMediaRepository extends JpaRepository<CompanyMedia, UUID> {
}
