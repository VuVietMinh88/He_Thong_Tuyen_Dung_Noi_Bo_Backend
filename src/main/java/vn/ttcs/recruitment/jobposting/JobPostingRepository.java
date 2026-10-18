package vn.ttcs.recruitment.jobposting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// Task 302: job postings (V21). Their authors (job_posting_authors) are not mapped here.
public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {
}
