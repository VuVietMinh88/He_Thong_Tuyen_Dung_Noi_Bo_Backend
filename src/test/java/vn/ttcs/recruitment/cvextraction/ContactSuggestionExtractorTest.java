package vn.ttcs.recruitment.cvextraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.cvextraction.CvExtractionResult.*;

class ContactSuggestionExtractorTest {
    private ContactSuggestionExtractor.Extraction extract(String text) {
        return new ContactSuggestionExtractor().extract(List.of(
                new ContactSuggestionExtractor.PageText(1, Source.TEXT, text)));
    }
    @Test void labelledVietnameseFieldsHaveOriginalAndNormalizedValues() {
        var result = extract("Họ và tên: Nguyễn Văn An\nEmail: An.Nguyen+cv@Example.test\nĐiện thoại: 0912 345 678");
        assertThat(result.complete()).isTrue();
        assertThat(result.suggestions().fullName().getFirst().value()).isEqualTo("Nguyễn Văn An");
        assertThat(result.suggestions().email().getFirst().normalizedValue()).isEqualTo("an.nguyen+cv@example.test");
        assertThat(result.suggestions().phone().getFirst().normalizedValue()).isEqualTo("+84912345678");
    }
    @Test void unicodeDecompositionAndWhitespaceDoNotLoseAccents() {
        var result = extract("Họ tên: Nguye\u0302\u0303n\u00a0Văn An\nEmail:\tAn@example.test\nPhone: +84 912 345 678");
        assertThat(result.suggestions().fullName().getFirst().value()).isEqualTo("Nguyễn Văn An");
        assertThat(result.complete()).isTrue();
    }
    @Test void preservesEmailPlusTagAndDotsAndDeduplicatesCase() {
        var result = extract("Email: First.Last+cv@example.test\nEmail: first.last+cv@EXAMPLE.TEST");
        assertThat(result.suggestions().email()).hasSize(1);
        assertThat(result.suggestions().email().getFirst().normalizedValue()).isEqualTo("first.last+cv@example.test");
    }
    @Test void equalCandidatesHaveNoPreferredValue() {
        var result = extract("Email: a@example.test\nEmail: b@example.test");
        assertThat(result.suggestions().email()).hasSize(2).noneMatch(Candidate::preferred);
        assertThat(result.warnings()).contains("EMAIL_AMBIGUOUS");
    }
    @Test void keepsReferenceContactButNeverPrefersItOverApplicantsContact() {
        var result = extract("Full name: Alice Nguyen\nEmail: alice@example.test\nPhone: 0912345678\nReferences\nFull name: Bob Nguyen\nEmail: bob@example.test\nPhone: 0987654321");
        assertThat(result.suggestions().email()).hasSize(2);
        assertThat(result.suggestions().email().getFirst()).matches(Candidate::preferred);
        assertThat(result.suggestions().email().get(1)).matches(c -> !c.preferred() && c.reason().equals("REFERENCE_SECTION"));
        assertThat(result.suggestions().fullName().getFirst().value()).isEqualTo("Alice Nguyen");
    }
    @Test void referenceOnlyIsNeverPreferred() {
        var result = extract("Người tham chiếu\nEmail: ref@example.test\nPhone: 0912345678");
        assertThat(result.suggestions().email()).noneMatch(Candidate::preferred);
        assertThat(result.warnings()).contains("EMAIL_REFERENCE_ONLY", "PHONE_REFERENCE_ONLY");
    }
    @ParameterizedTest @CsvSource({"'0912.345.678',+84912345678", "'+84 (912) 345-678',+84912345678", "'024 1234 5678',+842412345678"})
    void vietnamesePhones(String input, String expected) {
        assertThat(extract("Phone: " + input).suggestions().phone().getFirst().normalizedValue()).isEqualTo(expected);
    }
    @Test void foreignPhoneRequiresReviewAndKeepsItsCountryCode() {
        var result = extract("Phone: +1 (415) 555-2671");
        var candidate = result.suggestions().phone().getFirst();
        assertThat(candidate.normalizedValue()).isEqualTo("+14155552671");
        assertThat(candidate.preferred()).isFalse();
        assertThat(result.warnings()).contains("PHONE_INTERNATIONAL_REVIEW");
    }
    @ParameterizedTest @ValueSource(strings={"2024-10-15", "1234567890", "0123456789", "+84912", "091234567890123456"})
    void doesNotInventPhonesFromDatesAndArbitraryNumbers(String input) {
        assertThat(extract(input).suggestions().phone()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"Software Engineer", "Curriculum Vitae", "Contact Information", "Example Company", "Đại Học Hà Nội"})
    void doesNotTreatHeadingsAsNames(String input) {
        assertThat(extract(input).suggestions().fullName()).isEmpty();
    }
    @Test void readsNameOnFollowingLine() {
        assertThat(extract("Họ tên:\nNguyễn Văn An").suggestions().fullName().getFirst().value()).isEqualTo("Nguyễn Văn An");
    }
    @Test void twoUnlabelledHeaderNamesRemainAmbiguous() {
        var result = extract("Nguyễn Văn An\nTrần Văn Bình");
        assertThat(result.suggestions().fullName()).hasSize(2).noneMatch(Candidate::preferred);
        assertThat(result.warnings()).contains("NAME_AMBIGUOUS");
    }
    @Test void candidateAndLineBudgetsAreVisible() {
        var result = extract("a".repeat(1001) + "\n" + java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> "Email: person" + i + "@example.test").collect(java.util.stream.Collectors.joining("\n")));
        assertThat(result.suggestions().email()).hasSize(10);
        assertThat(result.warnings()).contains("LINE_LIMIT", "CANDIDATE_LIMIT");
    }
    @Test void pageAndSourceArePreservedWithoutPiiInToString() {
        var result = new ContactSuggestionExtractor().extract(List.of(new ContactSuggestionExtractor.PageText(
                2, Source.OCR, "Email: private@example.test")));
        var candidate = result.suggestions().email().getFirst();
        assertThat(candidate.page()).isEqualTo(2); assertThat(candidate.source()).isEqualTo(Source.OCR);
        assertThat(candidate.toString()).doesNotContain("private");
        assertThat(result.suggestions().toString()).doesNotContain("private");
    }
    @Test void normalizationIsIndependentOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(extract("Email: INFO@example.test").suggestions().email().getFirst().normalizedValue()).isEqualTo("info@example.test");
        } finally { Locale.setDefault(original); }
    }
    @Test void laterApplicantContactReplacesLowerRankedReferenceContactsAtLimit() {
        String refs = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "Email: ref" + i + "@example.test")
                .collect(java.util.stream.Collectors.joining("\n"));
        var result = extract("References\n" + refs + "\nContact Information\nEmail: applicant@example.test");
        assertThat(result.suggestions().email().getFirst().value()).isEqualTo("applicant@example.test");
        assertThat(result.suggestions().email().getFirst().preferred()).isTrue();
        assertThat(result.suggestions().email()).hasSize(10);
    }
    @Test void ocrStartsAtTopOfPageAfterTextLayerReferenceSection() {
        var result = new ContactSuggestionExtractor().extract(List.of(
                new ContactSuggestionExtractor.PageText(1, Source.TEXT, "References\nEmail: ref@example.test"),
                new ContactSuggestionExtractor.PageText(1, Source.OCR, "Email: applicant@example.test")));
        assertThat(result.suggestions().email().getFirst().value()).isEqualTo("applicant@example.test");
        assertThat(result.suggestions().email().getFirst().preferred()).isTrue();
    }
    @Test void emailContainingReferenceWordDoesNotBecomeAReferenceSection() {
        var result = extract("Email: references@example.test");
        assertThat(result.suggestions().email().getFirst().preferred()).isTrue();
        assertThat(result.warnings()).doesNotContain("EMAIL_REFERENCE_ONLY");
    }
    @ParameterizedTest @ValueSource(strings={"LIÊN HỆ", "GIỚI THIỆU", "APPLICATION PROFILE",
            "CHUYÊN VIÊN PHÁT TRIỂN PHẦN MỀM", "KỸ SƯ PHẦN MỀM", "THÔNG TIN ỨNG VIÊN"})
    void cvHeadingOrRoleCannotBeAnUnlabelledName(String heading) {
        assertThat(extract(heading).suggestions().fullName()).isEmpty();
    }
    @Test void sidebarTextKeepsOnlyTheApplicantName() {
        var result = extract("Nguyễn Minh Anh\nCHUYÊN VIÊN PHÁT TRIỂN PHẦN MỀM\nLIÊN HỆ\nGIỚI THIỆU\nEmail: minh.anh@example.test\nĐiện thoại: 0912345678");
        assertThat(result.suggestions().fullName()).hasSize(1);
        assertThat(result.suggestions().fullName().getFirst().value()).isEqualTo("Nguyễn Minh Anh");
        assertThat(result.complete()).isTrue();
    }
    @Test void contactHeadingAloneCannotMakeAReceiptComplete() {
        var result = extract("LIÊN HỆ\nEmail: minh.anh@example.test\nĐiện thoại: 0912345678");
        assertThat(result.complete()).isFalse();
        assertThat(result.suggestions().fullName()).isEmpty();
        assertThat(result.warnings()).contains("NAME_NOT_FOUND");
    }
    @Test void ocrNameInVisualHeaderIsFoundAfterReadingAWholeSidebarFirst() {
        String text = "LIÊN HỆ\n" + "\n".repeat(17) + "Nguyễn Minh Anh\nEmail: minh.anh@example.test\nPhone: 0912345678";
        var result = new ContactSuggestionExtractor().extract(List.of(new ContactSuggestionExtractor.PageText(
                1, Source.OCR, text, List.of(new OcrEngine.LinePosition(18, .37, .06, .025)))));
        assertThat(result.complete()).isTrue();
        assertThat(result.suggestions().fullName()).hasSize(1);
        assertThat(result.suggestions().fullName().getFirst()).matches(c -> c.preferred()
                && c.value().equals("Nguyễn Minh Anh") && c.reason().equals("HEADER_POSITION"));
    }
    @ParameterizedTest @CsvSource({".70,.025", ".06,.005"})
    void aLateBodyNameOrSmallCaptionCannotBecomeAProminentHeader(double top, double height) {
        String text = "\n".repeat(18) + "Michael Brown\nEmail: applicant@example.test\nPhone: 0912345678";
        var result = new ContactSuggestionExtractor().extract(List.of(new ContactSuggestionExtractor.PageText(
                1, Source.OCR, text, List.of(new OcrEngine.LinePosition(18, .4, top, height)))));
        assertThat(result.complete()).isFalse();
        assertThat(result.suggestions().fullName()).isEmpty();
    }
    @Test void equallyProminentUnlabelledNamesRemainAmbiguous() {
        String text = "\n".repeat(10) + "Nguyễn Văn An\nTrần Văn Bình";
        var result = new ContactSuggestionExtractor().extract(List.of(new ContactSuggestionExtractor.PageText(
                1, Source.TEXT, text, List.of(new OcrEngine.LinePosition(10, .2, .05, .025),
                new OcrEngine.LinePosition(11, .2, .10, .025)))));
        assertThat(result.suggestions().fullName()).hasSize(2).noneMatch(Candidate::preferred);
        assertThat(result.warnings()).contains("NAME_AMBIGUOUS");
    }
}
