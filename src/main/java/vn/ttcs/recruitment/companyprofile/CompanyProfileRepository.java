package vn.ttcs.recruitment.companyprofile;

import org.springframework.data.jpa.repository.JpaRepository;

// The only row has id CompanyProfile.SINGLETON_ID.
public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, Integer> {
}
