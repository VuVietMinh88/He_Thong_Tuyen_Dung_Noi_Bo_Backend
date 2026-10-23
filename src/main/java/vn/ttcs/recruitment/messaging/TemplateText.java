package vn.ttcs.recruitment.messaging;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 531/532: the rules of a template's text and how it is filled in. Pure functions, so the API, the preview and
 * every sender apply exactly the same rules.
 *
 * <p>A placeholder is {{name}} with a name of letters only, and the name must be one of the type's supported variables;
 * any other {{...}}, and any "{{" or "}}" left over, is refused rather than sent as typed. The subject is one line: a
 * line break or another control character would let a template add a mail header. The body is plain text with line
 * breaks; a blank line separates paragraphs. Neither may start an HTML tag: MailLayout escapes every value, so a tag
 * would only show up as text. A value inserted into a template is put on one line (control, format and separator
 * characters become a space), so a candidate's name cannot add lines or headers either.
 */
public final class TemplateText {
    public static final int MAX_SUBJECT_LENGTH = 200;
    public static final int MAX_BODY_LENGTH = 5000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]{0,64})}}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z]{1,32}");
    private static final Pattern HTML_START = Pattern.compile("<[A-Za-z/!?]");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n\\s*");
    private static final Pattern NOT_ONE_LINE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");

    private TemplateText() {
    }

    /** The problems of a draft, by field ("subject", "body"); empty when the draft may be saved. */
    public static Map<String, String> problems(EmailTemplateType type, String subject, String body) {
        Map<String, String> problems = new LinkedHashMap<>();
        String subjectProblem = textProblem(subject, MAX_SUBJECT_LENGTH, false);
        if (subjectProblem == null) subjectProblem = placeholderProblem(subject, type.variables());
        if (subjectProblem != null) problems.put("subject", subjectProblem);
        String bodyProblem = textProblem(body, MAX_BODY_LENGTH, true);
        if (bodyProblem == null) bodyProblem = placeholderProblem(body, type.variables());
        if (bodyProblem != null) problems.put("body", bodyProblem);
        return problems;
    }

    /** The placeholder names in the text that are not supported variables, in order of appearance. */
    public static Set<String> unknownVariables(String text, Collection<TemplateVariable> allowed) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean known = TemplateVariable.byPlaceholder(name).filter(allowed::contains).isPresent();
            if (!known) unknown.add(name);
        }
        return unknown;
    }

    /** The subject with every placeholder replaced; still one line. */
    public static String renderSubject(String subject, Map<TemplateVariable, String> values) {
        return oneLine(fill(subject, values));
    }

    /** The body with every placeholder replaced, as paragraphs; a paragraph keeps its single line breaks. */
    public static List<String> renderBody(String body, Map<TemplateVariable, String> values) {
        String filled = fill(body.replace("\r\n", "\n"), values);
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BREAK.split(filled)) {
            String text = paragraph.strip();
            if (!text.isEmpty()) paragraphs.add(text);
        }
        if (paragraphs.isEmpty()) throw new IllegalArgumentException("A rendered template needs at least one paragraph");
        return List.copyOf(paragraphs);
    }

    /** A value as a template inserts it: on one line, without surrounding spaces; null becomes empty. */
    public static String oneLine(String value) {
        return value == null ? "" : NOT_ONE_LINE.matcher(value).replaceAll(" ").strip();
    }

    private static String fill(String text, Map<TemplateVariable, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder(text.length() + 64);
        while (matcher.find()) {
            TemplateVariable variable = TemplateVariable.byPlaceholder(matcher.group(1))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown template variable"));
            matcher.appendReplacement(result, Matcher.quoteReplacement(oneLine(values.get(variable))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String textProblem(String text, int maxLength, boolean multiline) {
        if (text == null || text.isBlank()) return "Vui lòng nhập nội dung.";
        if (text.length() > maxLength) return "Tối đa " + maxLength + " ký tự.";
        boolean visible = false;
        for (int index = 0; index < text.length(); ) {
            int character = text.codePointAt(index);
            index += Character.charCount(character);
            if (Character.isISOControl(character) && !(multiline && (character == '\n' || character == '\t'))) {
                return multiline ? "Không được chứa ký tự điều khiển." : "Tiêu đề phải nằm trên một dòng, không chứa"
                        + " ký tự xuống dòng hoặc ký tự điều khiển.";
            }
            int kind = Character.getType(character);
            if (kind == Character.LINE_SEPARATOR || kind == Character.PARAGRAPH_SEPARATOR || kind == Character.SURROGATE
                    || (character >= 0x202A && character <= 0x202E) || (character >= 0x2066 && character <= 0x2069)) {
                return "Không được chứa ký tự phân cách dòng hoặc ký tự đổi chiều văn bản.";
            }
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character) && kind != Character.FORMAT) {
                visible = true;
            }
        }
        if (!visible) return "Vui lòng nhập nội dung có ký tự nhìn thấy được.";
        if (HTML_START.matcher(text).find()) return "Chỉ dùng văn bản thuần, không dùng thẻ HTML.";
        return null;
    }

    private static String placeholderProblem(String text, Collection<TemplateVariable> allowed) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        Set<String> invalid = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!NAME.matcher(name).matches()) invalid.add(name);
        }
        Set<String> unknown = unknownVariables(text, allowed);
        unknown.removeAll(invalid);
        String leftover = PLACEHOLDER.matcher(text).replaceAll("");
        // "{{{name}}}" would print a brace around the value: refused like any other stray brace pair.
        if (!invalid.isEmpty() || leftover.contains("{{") || leftover.contains("}}") || text.contains("{{{")
                || text.contains("}}}")) {
            return "Biến phải có dạng {{tenBien}} gồm chữ cái, ví dụ {{candidateName}}.";
        }
        if (!unknown.isEmpty()) {
            return "Biến không được hỗ trợ cho loại thư này: " + String.join(", ", unknown) + ". Biến hợp lệ: "
                    + String.join(", ", allowed.stream().map(TemplateVariable::placeholder).toList()) + ".";
        }
        return null;
    }
}
