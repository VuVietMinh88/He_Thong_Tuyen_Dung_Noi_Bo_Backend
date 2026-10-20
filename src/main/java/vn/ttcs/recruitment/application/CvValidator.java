package vn.ttcs.recruitment.application;

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
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Checks structure, not malware or extracted candidate data. Never renders a PDF or executes embedded content.
@Component
public class CvValidator {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2000;
    private static final int MAX_EXPANDED_BYTES = 100 * 1024 * 1024;
    private static final int MAX_XML_BYTES = 5 * 1024 * 1024;
    private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

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
            }
        }
    }

    private static void validateDocx(byte[] bytes) throws Exception {
        inspectZip(bytes);
        // Use POI's OOXML parser after bounded preflight, without changing its global settings used by Excel import.
        try (var pkg = OPCPackage.open(new ByteArrayInputStream(bytes)); var document = new XWPFDocument(pkg)) {
            if (!"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                    .equals(document.getPackagePart().getContentType()) || document.getDocument().getBody() == null) throw invalid();
        }
    }

    private static void inspectZip(byte[] bytes) throws Exception {
        var names = new HashSet<String>();
        long expanded = 0;
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
                if (xml) inspectXml(xmlBytes.toByteArray());
                zip.closeEntry();
            }
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("_rels/.rels") || !names.contains("word/document.xml")) {
            throw invalid();
        }
    }

    private static void inspectXml(byte[] bytes) throws Exception {
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
            @Override public void startElement(String uri, String localName, String name, Attributes attributes) throws SAXException {
                if (++depth > 128 || ++elements > 200000) throw new SAXException("CV XML complexity limit");
            }
            @Override public void endElement(String uri, String localName, String name) { depth--; }
            @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
            @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
        };
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (content[index] != prefix[index]) return false;
        return true;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "APPLICATION_CV_INVALID", "CV bị hỏng, trống hoặc không đọc được.");
    }
}
