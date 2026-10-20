package vn.ttcs.recruitment.talentpool;

import java.util.List;

/** Task 399 (story S5-06): one page of the talent pool search, newest entry first. */
public record TalentPoolPage(List<TalentPoolEntryView> items, long total, int page, int size, boolean hasNext) {
}
