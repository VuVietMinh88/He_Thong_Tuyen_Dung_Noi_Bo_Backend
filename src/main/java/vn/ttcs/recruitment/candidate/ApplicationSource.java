package vn.ttcs.recruitment.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Task 361 (story S4-09): where one application came from, as the unified profile shows it (the frontend's
 * ApplicationRecord.source). Every application of Sprint 4 so far came through the public career page, so type is
 * CAREER_PAGE "Trang tuyển dụng"; details is the name of the candidate-source catalog value the candidate chose
 * ("Nguồn biết tin", e.g. LinkedIn), left out when none was chosen. Referrals (task 350) add INTERNAL_REFERRAL. A
 * catalog name is staff data, not personal data, so toString stays the record's default.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationSource(String type, String label, String details) {
    static final String CAREER_PAGE = "CAREER_PAGE";
    static final String CAREER_PAGE_LABEL = "Trang tuyển dụng";

    static ApplicationSource careerPage(String catalogName) {
        return new ApplicationSource(CAREER_PAGE, CAREER_PAGE_LABEL, catalogName);
    }
}
