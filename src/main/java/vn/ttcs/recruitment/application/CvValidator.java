package vn.ttcs.recruitment.application;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import vn.ttcs.recruitment.common.ApiException;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Checks structure, not malware or extracted candidate data. Never renders a PDF or executes embedded content.
// Task 362 (S4-09): also refuses content a viewer would run, open or fetch from outside the file: a DOCX relationship
// outside the package other than a web or mail hyperlink or a local-drive path (allowedRelationship), and a PDF with a
// Launch or JavaScript action or an attached file (PdfActiveContent). Answer: 400 APPLICATION_CV_ACTIVE_CONTENT.
// A DOCX whose central directory lists other parts than its local headers is APPLICATION_CV_INVALID.
@Component
public class CvValidator {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2000;
    private static final int MAX_EXPANDED_BYTES = 100 * 1024 * 1024;
    private static final int MAX_XML_BYTES = 5 * 1024 * 1024;
    private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    // Task 362: the hyperlink relationship type of transitional and of strict OOXML.
    private static final String HYPERLINK = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";
    private static final String STRICT_HYPERLINK = "http://purl.oclc.org/ooxml/officeDocument/relationships/hyperlink";

    // The caller owns/closes the stream. The limit is on bytes read, not a client-provided size header.
    public ValidatedCv validate(String originalFilename, InputStream input) {
        if (input == null) throw invalid();
        String filename = safeFilename(originalFilename);
        byte[] bytes = readBounded(input);
        if (bytes.length == 0) throw invalid();
        String lower = filename.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".pdf") && startsWith(bytes, new byte[]{'%', 'P', 'D', 'F', '-'})) {
                validatePdf(bytes);
                return new ValidatedCv(filename, "application/pdf", bytes);
            }
            if (lower.endsWith(".docx") && startsWith(bytes, new byte[]{'P', 'K', 3, 4})) {
                validateDocx(bytes);
                return new ValidatedCv(filename, DOCX_TYPE, bytes);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            // Parser errors may include contents/filenames. Do not attach them to a public exception or log.
            throw invalid();
        }
        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "APPLICATION_CV_TYPE_UNSUPPORTED",
                "CV chỉ chấp nhận PDF hoặc DOCX có nội dung phù hợp với định dạng.");
    }

    private static byte[] readBounded(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "APPLICATION_CV_TOO_LARGE", "CV tối đa 10 MiB.");
            }
            return bytes;
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private static String safeFilename(String value) {
        if (value == null || value.codePoints().anyMatch(point -> Character.isISOControl(point)
                || Character.getType(point) == Character.FORMAT || Character.getType(point) == Character.SURROGATE)) throw invalid();
        // Browsers sometimes send a Windows fakepath. Store only a display name, never a filesystem path.
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.length() > 255 || name.codePoints().anyMatch(point ->
                Character.isISOControl(point) || Character.getType(point) == Character.FORMAT)) throw invalid();
        return name;
    }

    private static void validatePdf(byte[] bytes) throws IOException {
        try (var input = new RandomAccessReadBuffer(bytes)) {
            var parser = new PDFParser(input);
            try (var document = parser.parse(false)) {
                // Password-protected files are not readable CVs. Empty PDFs and excessive page trees are refused.
                if (document.isEncrypted() || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 1000) {
                    throw invalid();
                }
                // Task 362: every object, compressed ones included; too deep or too large is an IOException (invalid).
                if (PdfActiveContent.found(document.getDocument())) throw activeContent();
            }
        }
    }

    private static void validateDocx(byte[] bytes) throws Exception {
        // Task 362: reported only once every entry passed the structural checks, and before POI opens the package.
        if (inspectZip(bytes)) throw activeContent();
        // Use POI's OOXML parser after bounded preflight, without changing its global settings used by Excel import.
        try (var pkg = OPCPackage.open(new ByteArrayInputStream(bytes)); var document = new XWPFDocument(pkg)) {
            if (!"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                    .equals(document.getPackagePart().getContentType()) || document.getDocument().getBody() == null) throw invalid();
        }
    }

    // Task 362: true when a .rels part holds a relationship outside the package that is not allowed, in the parts read
    // here from the local headers or in those the central directory lists (inspectCentralDirectory).
    private static boolean inspectZip(byte[] bytes) throws Exception {
        // In the order of the local headers.
        var names = new LinkedHashSet<String>();
        long expanded = 0;
        boolean external = false;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (!names.add(name) || names.size() > MAX_ZIP_ENTRIES || name.startsWith("/")
                        || name.contains("\\") || name.contains("..") || name.indexOf(':') >= 0
                        || name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) throw invalid();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean xml = lower.endsWith(".xml") || lower.endsWith(".rels");
                var xmlBytes = xml ? new ByteArrayOutputStream() : null;
                long entryBytes = 0;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    entryBytes += read;
                    if (expanded > MAX_EXPANDED_BYTES || (xml && entryBytes > MAX_XML_BYTES)) throw invalid();
                    if (xml) xmlBytes.write(buffer, 0, read);
                }
                // Entry sizes are available after its data descriptor has been consumed, not from an untrusted header.
                if (entryBytes > 1024 * 1024 && (entry.getCompressedSize() <= 0
                        || entryBytes > entry.getCompressedSize() * 100)) throw invalid();
                if (xml && inspectXml(xmlBytes.toByteArray(), lower.endsWith(".rels"))) external = true;
                zip.closeEntry();
            }
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("_rels/.rels") || !names.contains("word/document.xml")) {
            throw invalid();
        }
        boolean centralExternal = inspectCentralDirectory(bytes, List.copyOf(names));
        return external || centralExternal;
    }

    // Task 362: ZipInputStream (above) and POI walk the local headers from the start of the file; Word, like any ZIP
    // reader that seeks, finds the parts through the central directory at its end. One file can show the two readers
    // different parts: a part renamed in its local header only, parts behind a fake central directory signature that
    // stops the walk, or a central entry pointing at a second local header hidden inside another part's data. So the
    // central directory must list exactly the parts walked above, with the same names in the same order (else
    // APPLICATION_CV_INVALID), and every .rels part is read again from where the central directory points. A part
    // that an Info-ZIP Unicode Path extra field names differently gets that name here (commons-compress's default), so
    // it no longer matches the name ZipInputStream read.
    private static boolean inspectCentralDirectory(byte[] bytes, List<String> walked) throws Exception {
        try (var zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            List<ZipArchiveEntry> entries = Collections.list(zip.getEntries());
            if (!entries.stream().map(ZipArchiveEntry::getName).toList().equals(walked)) throw invalid();
            long expanded = 0;
            boolean external = false;
            for (ZipArchiveEntry entry : entries) {
                if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".rels")) continue;
                byte[] xml;
                try (var input = zip.getInputStream(entry)) {
                    xml = input.readNBytes(MAX_XML_BYTES + 1);
                }
                // Central entries may share data: the limits of the walk do not bound this second read.
                expanded += xml.length;
                if (xml.length > MAX_XML_BYTES || expanded > MAX_EXPANDED_BYTES) throw invalid();
                if (inspectXml(xml, true)) external = true;
            }
            return external;
        }
    }

    // Task 362: for a .rels part (relationships), true when one Relationship is not allowed. The handler only sets a
    // flag that the caller maps after the parse; no answer depends on an exception thrown from a SAX callback.
    private static boolean inspectXml(byte[] bytes, boolean relationships) throws Exception {
        var factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var reader = factory.newSAXParser().getXMLReader();
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var handler = new DefaultHandler() {
            private int depth;
            private int elements;
            private boolean external;
            @Override public void startElement(String uri, String localName, String name, Attributes attributes) throws SAXException {
                if (++depth > 128 || ++elements > 200000) throw new SAXException("CV XML complexity limit");
                // Any namespace: a Relationship element that only looks foreign is still checked (fail closed).
                if (relationships && "Relationship".equals(localName) && !allowedRelationship(attributes.getValue("", "TargetMode"),
                        attributes.getValue("", "Type"), attributes.getValue("", "Target"))) external = true;
            }
            @Override public void endElement(String uri, String localName, String name) { depth--; }
            @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
            @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
        };
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        return handler.external;
    }

    // Task 362: may Word resolve this relationship? Without TargetMode, or with Internal, it points inside the package.
    // Any other TargetMode counts as External (compared trimmed, ignoring ASCII case; an unknown value fails closed).
    // An external hyperlink is allowed only to http, https or mailto (ignoring case): a click opens a browser or a mail
    // program. Any other external relationship (attachedTemplate, oleObject, frame, subDocument, a linked image) is
    // allowed only to a path on a local drive of the author's machine, without a host, as Word leaves in a CV made from
    // a local template: file:///X:/..., file:///X:\..., X:\... or X:/... with one ASCII drive letter. Everything else
    // is refused: a UNC path or any host (Word fetches from it or signs in to it, which leaks the reader's NTLM hash), a
    // web address, a relative or empty target, and a hyperlink to file: or to a UNC path.
    static boolean allowedRelationship(String targetMode, String type, String target) {
        if (targetMode == null || equalsIgnoringAsciiCase(targetMode.strip(), "internal")) return true;
        if (target == null) return false;
        if (HYPERLINK.equals(type) || STRICT_HYPERLINK.equals(type)) {
            return startsWithIgnoringCase(target, "http://") || startsWithIgnoringCase(target, "https://")
                    || startsWithIgnoringCase(target, "mailto:");
        }
        String path = startsWithIgnoringCase(target, "file:///") ? target.substring("file:///".length()) : target;
        return path.length() >= 3 && isAsciiLetter(path.charAt(0)) && path.charAt(1) == ':'
                && (path.charAt(2) == '/' || path.charAt(2) == '\\');
    }

    // ASCII only: String.equalsIgnoreCase and regionMatches would also match the long s (U+017F) to "s" and the
    // dotless i (U+0131) to "i", which Word does not read as the same scheme or mode. lowerPrefix is lower-case ASCII.
    private static boolean startsWithIgnoringCase(String value, String lowerPrefix) {
        if (value.length() < lowerPrefix.length()) return false;
        for (int index = 0; index < lowerPrefix.length(); index++) {
            char actual = value.charAt(index);
            if (actual != lowerPrefix.charAt(index)
                    && !(isAsciiLetter(actual) && (char) (actual | 0x20) == lowerPrefix.charAt(index))) return false;
        }
        return true;
    }

    private static boolean equalsIgnoringAsciiCase(String value, String lower) {
        return value.length() == lower.length() && startsWithIgnoringCase(value, lower);
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (content[index] != prefix[index]) return false;
        return true;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "APPLICATION_CV_INVALID", "CV bị hỏng, trống hoặc không đọc được.");
    }

    // Task 362: the body names no file, part or relationship.
    private static ApiException activeContent() {
        return new ApiException(HttpStatus.BAD_REQUEST, "APPLICATION_CV_ACTIVE_CONTENT",
                "CV có nội dung tự động mở tệp, mẫu hoặc chương trình bên ngoài, hoặc có tệp đính kèm. "
                        + "Vui lòng lưu lại CV dưới dạng PDF thông thường rồi gửi lại.");
    }
}
