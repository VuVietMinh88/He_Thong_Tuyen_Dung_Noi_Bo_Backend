package vn.ttcs.recruitment.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Task 361 (story S4-09): where one application came from, as the unified profile shows it (the frontend's
 * ApplicationRecord.source). An application of the public career page is type CAREER_PAGE "Trang tuyển dụng"; details
 * is the name of the candidate-source catalog value the candidate chose ("Nguồn biết tin", e.g. LinkedIn), left out
 * when none was chosen.
 * <p>
 * Task 350 (story S4-07, "Hồ sơ được gắn nhãn nguồn Giới thiệu nội bộ và tên người giới thiệu"): an application with a
 * referral row (V36) is type INTERNAL_REFERRAL "Giới thiệu nội bộ", whatever the catalog says (a referred application
 * has no catalog source); referrerId and referrerName name the staff member who referred (id and current full name,
 * never an email, the house rule for staff), note is the referrer's note as stored (line breaks \n). These three are
 * left out for a career-page application; details is left out for a referral. Like every field of the profile, they
 * reach only a reader who may read that application. toString hides the names and the note.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationSource(String type, String label, String details, UUID referrerId, String referrerName,
                                String note) {
    static final String CAREER_PAGE = "CAREER_PAGE";
    static final String CAREER_PAGE_LABEL = "Trang tuyển dụng";
    static final String INTERNAL_REFERRAL = "INTERNAL_REFERRAL";
    static final String INTERNAL_REFERRAL_LABEL = "Giới thiệu nội bộ";

    static ApplicationSource careerPage(String catalogName) {
        return new ApplicationSource(CAREER_PAGE, CAREER_PAGE_LABEL, catalogName, null, null, null);
    }

    static ApplicationSource referral(UUID referrerId, String referrerName, String note) {
        return new ApplicationSource(INTERNAL_REFERRAL, INTERNAL_REFERRAL_LABEL, null, referrerId, referrerName, note);
    }

    @Override
    public String toString() {
        return "ApplicationSource[" + type + (referrerId == null ? "" : ", referrer " + referrerId)
                + ", names and note redacted]";
    }
}
