package vn.ttcs.recruitment.application;

import java.util.List;

/**
 * Task 408 (story S5-08): what the duplicate check found among the applications the caller may read.
 * emailInRequisition says the entry would be refused (409 APPLICATION_ALREADY_SUBMITTED); sameEmail are the
 * applications the entry would be grouped with in one candidate profile; samePhone are applications of other emails
 * that use the same phone number, for a comparison by hand.
 */
public record ManualDuplicateCheckView(boolean emailInRequisition, List<ManualApplicationDuplicates.Match> sameEmail,
                                       List<ManualApplicationDuplicates.Match> samePhone) {
}
