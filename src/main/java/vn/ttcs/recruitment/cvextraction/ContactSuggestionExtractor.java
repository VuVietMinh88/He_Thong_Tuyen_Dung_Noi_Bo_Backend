package vn.ttcs.recruitment.cvextraction;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static vn.ttcs.recruitment.cvextraction.CvExtractionResult.*;

/** Conservative, explainable rules. Scores are rankings, never calibrated probabilities. */
final class ContactSuggestionExtractor {
    record PageText(int page, Source source, String text, List<OcrEngine.LinePosition> positions) {
        PageText(int page, Source source, String text) { this(page, source, text, List.of()); }
        PageText { positions = List.copyOf(positions); }
        @Override public String toString() { return "PageText[content redacted]"; }
    }
    record Extraction(Suggestions suggestions, List<String> warnings, boolean complete) { }
    private record Ranked(String value, String normalized, String reason, int page, Source source, int rank,
                          boolean foreign) {
        @Override public String toString() { return "Ranked[personal data redacted]"; }
    }
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\p{L}\\p{N}.!#$%&'*+/=?^_`{|}~@-])([A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+)(?![\\p{L}\\p{N}@-])");
    private static final Pattern PHONE = Pattern.compile("(?<![\\p{L}\\p{N}])\\+?\\d[\\d ().-]{6,28}\\d(?![\\p{L}\\p{N}])");
    private static final Pattern NAME_LABEL = Pattern.compile("^(?:ho(?: va)? ten|full name|name)\\s*[:：-]\\s*(.*)$");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{M}]+(?:[ '\u2019-][\\p{L}\\p{M}]+){1,6}");
    private static final Set<String> NOT_NAMES = Set.of("curriculum vitae", "resume", "personal information",
            "contact information", "thong tin ca nhan", "thong tin lien he", "work experience", "kinh nghiem",
            "software engineer", "software developer", "project manager", "business analyst", "data analyst",
            "human resources", "cover letter", "ngay sinh", "date of birth", "objective", "muc tieu",
            "education", "hoc van", "references", "nguoi tham chieu", "nguoi tham khao",
            "lien he", "gioi thieu", "application profile", "candidate profile", "professional profile",
            "professional summary", "career summary", "technical skills", "ky nang", "ky nang chuyen mon",
            "ho so ung tuyen", "thong tin ung vien", "ho so nang luc", "muc tieu nghe nghiep");

    Extraction extract(List<PageText> pages) {
        var names = new LinkedHashMap<String, Ranked>();
        var emails = new LinkedHashMap<String, Ranked>();
        var phones = new LinkedHashMap<String, Ranked>();
        var warnings = new LinkedHashSet<String>();
        boolean reference = false;
        boolean contact = false;
        for (PageText page : pages) {
            // OCR repeats the same page from its top, not a continuation of the text layer's reference section.
            if (page.source() == Source.OCR) { reference = false; contact = false; }
            String[] lines = normalize(page.text()).split("\\R", -1);
            var positions = new java.util.HashMap<Integer, OcrEngine.LinePosition>();
            page.positions().forEach(position -> positions.put(position.index(), position));
            boolean nextName = false;
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index].strip();
                if (line.length() > CvExtractionLimits.MAX_LINE) { warnings.add("LINE_LIMIT"); continue; }
                String folded = fold(line);
                if (folded.matches("(?:nguoi tham (?:chieu|khao)|references?|referees?)(?:\\s*[:：].*|\\s+available.*|\\s*)")) reference = true;
                if (folded.matches("(?:thong tin (?:ca nhan|lien he)|personal (?:details|information)|contact(?: information)?)\\s*:?")) {
                    contact = true; reference = false;
                }
                if (folded.matches("(?:education|experience|skills|hoc van|kinh nghiem|ky nang)\\s*:?")) contact = false;
                int rank = reference ? -100 : contact ? 30 : page.page() == 1 && index < 15 ? 20 : 10;
                String reason = reference ? "REFERENCE_SECTION" : contact ? "CONTACT_SECTION" : "TEXT_MATCH";
                var emailMatcher = EMAIL.matcher(line);
                while (emailMatcher.find()) {
                    String value = emailMatcher.group(1);
                    if (value.length() <= 254 && value.substring(0, value.indexOf('@')).length() <= 64) {
                        boolean labelled = folded.matches(".*(?:e-?mail)\\s*[:：].*");
                        add(emails, new Ranked(value, value.toLowerCase(Locale.ROOT),
                                !reference && labelled ? "EXPLICIT_LABEL" : reason, page.page(), page.source(),
                                rank + (!reference && labelled ? 20 : 0), false), warnings);
                    }
                }
                var phoneMatcher = PHONE.matcher(line);
                while (phoneMatcher.find()) {
                    String value = phoneMatcher.group().strip();
                    String digits = value.replaceAll("[ ().-]", "");
                    boolean vietnam = digits.matches("0[35789][0-9]{8}") || digits.matches("\\+84[35789][0-9]{8}")
                            || digits.matches("0(?:2[0-9]{9})") || digits.matches("\\+842[0-9]{9}");
                    boolean foreign = !vietnam && digits.matches("\\+[1-9][0-9]{7,14}") && !digits.startsWith("+84");
                    if (!vietnam && !foreign) continue;
                    boolean labelled = folded.matches(".*(?:dien thoai|phone|mobile|tel|sdt)\\s*[:：].*");
                    String normalized = vietnam && digits.startsWith("0") ? "+84" + digits.substring(1) : digits;
                    add(phones, new Ranked(value, normalized, reference ? "REFERENCE_SECTION"
                            : foreign ? "PHONE_INTERNATIONAL" : labelled ? "EXPLICIT_LABEL" : reason,
                            page.page(), page.source(), rank + (!reference && labelled ? 20 : 0), foreign), warnings);
                }
                var nameLabel = NAME_LABEL.matcher(folded);
                boolean labelledName = nameLabel.matches();
                OcrEngine.LinePosition position = positions.get(index);
                boolean prominentHeader = page.page() == 1 && position != null && position.top() <= .30 && position.height() >= .012;
                String name = null;
                if (labelledName) {
                    int separator = Math.max(line.indexOf(':'), line.indexOf('：'));
                    if (separator < 0) separator = line.indexOf('-');
                    name = separator >= 0 ? line.substring(separator + 1).strip() : null;
                    nextName = name != null && name.isEmpty();
                } else if (nextName && !line.isEmpty()) { name = line; labelledName = true; nextName = false; }
                else if (page.page() == 1 && (index < 6 || prominentHeader) && looksLikeName(line) && titleCase(line)) name = line;
                if (name != null && looksLikeName(name)) {
                    add(names, new Ranked(name, fold(name), reference ? "REFERENCE_SECTION"
                            : labelledName ? "EXPLICIT_LABEL" : prominentHeader ? "HEADER_POSITION" : "HEADER", page.page(), page.source(),
                            reference ? -100 : labelledName ? 60 : prominentHeader ? 40 : 20, false), warnings);
                }
            }
        }
        List<Candidate> nameList = candidates(names, "NAME", warnings);
        List<Candidate> emailList = candidates(emails, "EMAIL", warnings);
        List<Candidate> phoneList = candidates(phones, "PHONE", warnings);
        boolean complete = List.of(nameList, emailList, phoneList).stream().allMatch(list -> list.stream().anyMatch(Candidate::preferred));
        return new Extraction(new Suggestions(nameList, emailList, phoneList), List.copyOf(warnings), complete);
    }

    private static boolean looksLikeName(String name) {
        String folded = fold(name);
        return name.length() <= 120 && NAME.matcher(name).matches() && !NOT_NAMES.contains(folded)
                && !folded.matches("(?:ky su|chuyen vien|nhan vien|truong nhom|lap trinh vien)\\b.*")
                && !folded.matches(".*\\b(?:engineer|developer|manager|analyst|university|company|cong ty|dai hoc|curriculum|resume|experience|information)\\b.*");
    }
    private static boolean titleCase(String name) {
        return name.codePoints().anyMatch(Character::isUpperCase)
                && List.of(name.split("[ '\u2019-]")).stream().allMatch(word -> Character.isUpperCase(word.codePointAt(0)));
    }
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replace('\u00a0', ' ')
                .replaceAll("[\\p{Cf}\\p{Cc}&&[^\\r\\n\\t]]", "").replaceAll("[\\t\\p{Zs}]+", " ");
    }
    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).strip();
    }
    private static void add(Map<String, Ranked> values, Ranked candidate, Set<String> warnings) {
        Ranked old = values.get(candidate.normalized());
        if (old != null) { if (candidate.rank() > old.rank()) values.put(candidate.normalized(), candidate); return; }
        if (values.size() >= CvExtractionLimits.MAX_CANDIDATES) {
            warnings.add("CANDIDATE_LIMIT");
            var lowest = values.entrySet().stream().min(Comparator.comparingInt(entry -> entry.getValue().rank())).orElseThrow();
            if (candidate.rank() <= lowest.getValue().rank()) return;
            values.remove(lowest.getKey());
        }
        values.put(candidate.normalized(), candidate);
    }
    private static List<Candidate> candidates(Map<String, Ranked> values, String field, Set<String> warnings) {
        var sorted = values.values().stream().sorted(Comparator.comparingInt(Ranked::rank).reversed()).toList();
        if (sorted.isEmpty()) { warnings.add(field + "_NOT_FOUND"); return List.of(); }
        int top = sorted.getFirst().rank();
        boolean ambiguous = sorted.stream().filter(value -> value.rank() == top).count() > 1;
        if (ambiguous) warnings.add(field + "_AMBIGUOUS");
        if (sorted.stream().anyMatch(Ranked::foreign)) warnings.add("PHONE_INTERNATIONAL_REVIEW");
        if (top < 0) warnings.add(field + "_REFERENCE_ONLY");
        var result = new ArrayList<Candidate>();
        for (Ranked value : sorted) result.add(new Candidate(value.value(), value.normalized(), value.reason(),
                value.page(), value.source(), !ambiguous && value.rank() == top && top >= 0 && !value.foreign()));
        return List.copyOf(result);
    }
}
