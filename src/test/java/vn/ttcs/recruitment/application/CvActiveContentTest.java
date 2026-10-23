package vn.ttcs.recruitment.application;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ttcs.recruitment.common.ApiException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Task 362 (story S4-09, decision D21 and orchestrator decision 3): CvValidator refuses a CV whose DOCX points outside
 * the package or whose PDF runs, opens or attaches something, with 400 APPLICATION_CV_ACTIVE_CONTENT; structure errors
 * stay APPLICATION_CV_INVALID. Fixtures start from TRAN DUC LUONG's own CvValidatorTest documents (POI, PDFBox) and are
 * rewritten as ZIP entries or COS objects, or written by hand where a byte-exact structure is needed.
 */
class CvActiveContentTest {
    private static final String ACTIVE = "APPLICATION_CV_ACTIVE_CONTENT";
    private static final String INVALID = "APPLICATION_CV_INVALID";
    private static final String MESSAGE = "CV có nội dung tự động mở tệp, mẫu hoặc chương trình bên ngoài, hoặc có tệp"
            + " đính kèm. Vui lòng lưu lại CV dưới dạng PDF thông thường rồi gửi lại.";
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final String TEMPLATE = RELATIONSHIPS + "attachedTemplate";
    private static final String HYPERLINK = RELATIONSHIPS + "hyperlink";
    private static final String STRICT_HYPERLINK = "http://purl.oclc.org/ooxml/officeDocument/relationships/hyperlink";
    private static final String IMAGE = RELATIONSHIPS + "image";
    private static final String SETTINGS_RELS = "word/_rels/settings.xml.rels";
    private static final String DOCUMENT_RELS = "word/_rels/document.xml.rels";
    // A name the error body must never repeat.
    private static final String SECRET_NAME = "CV bi mat cua ung vien";
    // Relationships parts for the hand-written ZIP files: a remote template, and one without any relationship.
    private static final byte[] REMOTE_TEMPLATE = relationshipsPart(relationship(TEMPLATE,
            "file://attacker.example.test/share/cv.dotm", "External"));
    private static final byte[] PLAIN_RELS = relationshipsPart("");

    private final CvValidator validator = new CvValidator();

    // --- DOCX ---------------------------------------------------------------------------------------------------

    // An external template: Word fetches it when the document opens (a web address), or signs in to the host and
    // sends the reader's NTLM hash (a UNC path or a file: URL with a host).
    @ParameterizedTest
    @ValueSource(strings = {"https://example.test/t.dotm", "http://example.test/t.dotm", "\\\\server\\share\\t.dotm",
            "//server/share/t.dotm", "\\server\\share\\t.dotm", "file://server/share/t.dotm",
            "file:////server/share/t.dotm", "FILE://server/share/t.dotm", "ftp://example.test/t.dotm", "t.dotm",
            "../t.dotm", "", "file:t.dotm", "file:/C:/t.dotm", "C:t.dotm", "1:/t.dotm"})
    void anExternalTemplateOutsideALocalDriveIsRefused(String target) throws Exception {
        assertDocx(docxWith(SETTINGS_RELS, relationship(TEMPLATE, target, "External")), ACTIVE);
    }

    // A CV made from a Word template on the author's own drive keeps that path: it is not a host and is accepted.
    @ParameterizedTest
    @ValueSource(strings = {"file:///C:/Users/a/AppData/Roaming/Microsoft/Templates/CV.dotx",
            "file:///c:\\Users\\a\\Templates\\CV.dotx", "C:\\Users\\a\\Templates\\CV.dotx", "d:/Templates/CV.dotx"})
    void anExternalTemplateOnALocalDriveIsAccepted(String target) throws Exception {
        assertAccepted(docxWith(SETTINGS_RELS, relationship(TEMPLATE, target, "External")));
    }

    // Linked OLE objects, frames, sub-documents and linked images outside a local drive are refused.
    @ParameterizedTest
    @ValueSource(strings = {"oleObject", "frame", "subDocument", "image"})
    void otherExternalRelationshipsToAHostAreRefused(String type) throws Exception {
        assertDocx(docxWith(DOCUMENT_RELS, relationship(RELATIONSHIPS + type, "https://example.test/x", "External")),
                ACTIVE);
        assertDocx(docxWith(DOCUMENT_RELS, relationship(RELATIONSHIPS + type, "\\\\server\\share\\x", "External")),
                ACTIVE);
    }

    @Test
    void anExternalImageOnALocalDriveIsAccepted() throws Exception {
        assertAccepted(docxWith(DOCUMENT_RELS, relationship(IMAGE, "C:\\img\\a.png", "External")));
    }

    // TargetMode is compared trimmed and ignoring case; any value other than Internal fails closed.
    @ParameterizedTest
    @ValueSource(strings = {"external", "EXTERNAL", " External ", "Externe", ""})
    void everyTargetModeOtherThanInternalCountsAsExternal(String mode) throws Exception {
        assertDocx(docxWith(SETTINGS_RELS, relationship(TEMPLATE, "https://example.test/t.dotm", mode)), ACTIVE);
    }

    // Only ASCII letters match ignoring case: the dotless i (U+0131) and the long s (U+017F), which Java's
    // equalsIgnoreCase and regionMatches would fold to "i" and "s", do not make a mode Internal or a scheme web or local.
    @Test
    void lookAlikeLettersDoNotPassForAsciiOnes() throws Exception {
        String dotlessI = Character.toString(0x0131);
        String longS = Character.toString(0x017F);
        assertDocx(docxWith(SETTINGS_RELS, relationship(TEMPLATE, "https://example.test/t.dotm",
                dotlessI + "nternal")), ACTIVE);
        assertDocx(docxWith(DOCUMENT_RELS, relationship(HYPERLINK, "http" + longS + "://example.test", "External")),
                ACTIVE);
        assertDocx(docxWith(DOCUMENT_RELS, relationship(HYPERLINK, "ma" + dotlessI + "lto:a@example.test", "External")),
                ACTIVE);
        assertDocx(docxWith(SETTINGS_RELS, relationship(TEMPLATE, "f" + dotlessI + "le:///C:/t.dotx", "External")),
                ACTIVE);
    }

    // An internal relationship points at a part inside the package, whatever its target looks like.
    @ParameterizedTest
    @ValueSource(strings = {"Internal", "internal", " Internal "})
    void anInternalTargetModeIsNotChecked(String mode) {
        assertThat(CvValidator.allowedRelationship(mode, TEMPLATE, "https://example.test/t.dotm")).isTrue();
        assertThat(CvValidator.allowedRelationship(null, TEMPLATE, "https://example.test/t.dotm")).isTrue();
    }

    // A web or mail hyperlink only opens on a click, in a browser or a mail program: accepted in both OOXML flavours.
    @ParameterizedTest
    @ValueSource(strings = {"http://example.test/portfolio", "https://example.test/cv", "mailto:lan@example.test",
            "HTTPS://EXAMPLE.TEST/CV", "MailTo:lan@example.test"})
    void aWebOrMailHyperlinkIsAccepted(String target) throws Exception {
        assertAccepted(docxWith(DOCUMENT_RELS, relationship(HYPERLINK, target, "External")));
        assertAccepted(docxWith(DOCUMENT_RELS, relationship(STRICT_HYPERLINK, target, "External")));
    }

    // Any other scheme, a local or UNC path, or no target at all is refused for a hyperlink, local drives included.
    @ParameterizedTest
    @ValueSource(strings = {"file:///C:/Windows/System32/calc.exe", "file://server/share/x.exe", "\\\\server\\share\\x",
            "C:\\x.exe", "javascript:alert(1)", "ftp://example.test/x", "ms-word:ofe|u|https://example.test/x.docx",
            " https://example.test", ""})
    void aHyperlinkToAnythingButTheWebOrMailIsRefused(String target) throws Exception {
        assertDocx(docxWith(DOCUMENT_RELS, relationship(HYPERLINK, target, "External")), ACTIVE);
    }

    // A Relationship without a Target is refused as soon as it is external.
    @Test
    void anExternalRelationshipWithoutATargetIsRefused() throws Exception {
        assertDocx(docxWith(SETTINGS_RELS, "<Relationship Id=\"rId362\" Type=\"" + TEMPLATE
                + "\" TargetMode=\"External\"/>"), ACTIVE);
    }

    // The teammate's own document has internal relationships only.
    @Test
    void thePlainDocumentOfTheTeammatesTestIsAccepted() throws Exception {
        assertAccepted(CvValidatorTest.docx());
    }

    // Every .rels part is read, whatever its name or folder; other XML parts are not read for relationships.
    @Test
    void everyRelationshipsPartIsChecked() throws Exception {
        assertDocx(docxWith("_rels/.rels", relationship(TEMPLATE, "https://example.test/t.dotm", "External")), ACTIVE);
        assertDocx(docxWith("word/_rels/header1.xml.RELS", relationship(TEMPLATE, "https://example.test/t.dotm",
                "External")), ACTIVE);
        assertAccepted(withEntry(CvValidatorTest.docx(), "customXml/item1.xml",
                ("<x>" + relationship(TEMPLATE, "https://example.test/t.dotm", "External") + "</x>")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    // A structural error still answers APPLICATION_CV_INVALID, even next to an external relationship.
    @Test
    void aStructuralErrorStaysInvalid() throws Exception {
        byte[] external = docxWith(SETTINGS_RELS, relationship(TEMPLATE, "https://example.test/t.dotm", "External"));
        assertDocx(withEntry(external, "word/vbaProject.bin", new byte[] {1}), INVALID);
        assertDocx(withEntry(external, "word/broken.xml", "<a>".getBytes(StandardCharsets.UTF_8)), INVALID);
    }

    // ZipInputStream and POI read the local headers one after another from the start of the file; Word finds the parts
    // through the central directory at its end. A part renamed in its local header only (here to a name of the same
    // length ending in .xml) would be read as plain XML by the first and as a relationships part by Word.
    @Test
    void aPartRenamedInItsLocalHeaderOnlyIsInvalid() throws Exception {
        String disguise = "word/_rels/settings.xmlA.xml";
        assertThat(disguise).hasSameSizeAs(SETTINGS_RELS);
        // Written the same way without the trick, the document is accepted and the template is found.
        assertAccepted(handDocx().bytes());
        assertDocx(handDocx().part(SETTINGS_RELS, REMOTE_TEMPLATE).bytes(), ACTIVE);

        var renamed = handDocx();
        renamed.central(SETTINGS_RELS, REMOTE_TEMPLATE, renamed.local(disguise, REMOTE_TEMPLATE));
        byte[] bytes = renamed.bytes();
        assertThat(streamedNames(bytes)).contains(disguise).doesNotContain(SETTINGS_RELS);
        assertThat(centralNames(bytes)).contains(SETTINGS_RELS).doesNotContain(disguise);
        assertDocx(bytes, INVALID);

        // The two views must agree even when neither holds active content.
        var harmless = handDocx();
        harmless.central(SETTINGS_RELS, PLAIN_RELS, harmless.local(disguise, PLAIN_RELS));
        assertDocx(harmless.bytes(), INVALID);
    }

    // A fake central directory signature stops ZipInputStream (and POI) before the parts behind it, while the real
    // central directory still lists them for Word.
    @Test
    void partsHiddenBehindAFakeCentralDirectorySignatureAreInvalid() throws Exception {
        byte[] fakeSignature = {'P', 'K', 1, 2};
        byte[] bytes = handDocx().raw(fakeSignature).part(SETTINGS_RELS, REMOTE_TEMPLATE).bytes();
        assertThat(streamedNames(bytes)).doesNotContain(SETTINGS_RELS);
        assertThat(centralNames(bytes)).contains(SETTINGS_RELS);
        assertDocx(bytes, INVALID);
        // A structural error, so it wins over a template the walk found as well.
        assertDocx(handDocx().part(SETTINGS_RELS, REMOTE_TEMPLATE).raw(fakeSignature)
                .part("word/_rels/hidden.xml.rels", PLAIN_RELS).bytes(), INVALID);
    }

    // The central directory lists the parts in the order of their local headers, as every ZIP writer does.
    @Test
    void aCentralDirectoryInAnotherOrderIsInvalid() throws Exception {
        var reordered = handDocx();
        int rels = reordered.local(SETTINGS_RELS, PLAIN_RELS);
        int blob = reordered.local("word/media/blob.bin", new byte[] {1, 2, 3});
        reordered.central("word/media/blob.bin", new byte[] {1, 2, 3}, blob).central(SETTINGS_RELS, PLAIN_RELS, rels);
        byte[] bytes = reordered.bytes();
        assertThat(streamedNames(bytes)).containsExactlyInAnyOrderElementsOf(centralNames(bytes))
                .isNotEqualTo(centralNames(bytes));
        assertDocx(bytes, INVALID);
    }

    // An Info-ZIP Unicode Path extra field gives a part a second name, which some ZIP readers use instead of the
    // name bytes; here it makes a part named as plain XML a relationships part.
    @Test
    void aUnicodePathExtraFieldNamingAnotherPartIsInvalid() throws Exception {
        String disguise = "word/_rels/settings.xmlA.xml";
        var named = handDocx();
        named.central(disguise, REMOTE_TEMPLATE, named.local(disguise, REMOTE_TEMPLATE),
                HandZip.unicodePath(disguise, SETTINGS_RELS));
        byte[] bytes = named.bytes();
        assertThat(streamedNames(bytes)).contains(disguise);
        assertThat(centralNames(bytes)).contains(SETTINGS_RELS).doesNotContain(disguise);
        assertDocx(bytes, INVALID);
    }

    // The second read keeps the limits of the walk (CvValidator: 5 MiB per XML part, 100 MiB in all), since a central
    // entry may point at a part the walk read as another type, or share another entry's data.
    @Test
    void theSecondReadKeepsTheLimitsOfTheWalk() throws Exception {
        // A relationships part over 5 MiB (spaces after its root element), which the walk reads as a .bin part.
        String blob = "word/media/blob.bin";
        byte[] oversized = (new String(PLAIN_RELS, StandardCharsets.UTF_8) + " ".repeat(5 * 1024 * 1024))
                .getBytes(StandardCharsets.UTF_8);
        var pointed = handDocx();
        int blobAt = pointed.local(blob, oversized);
        pointed.central(blob, oversized, blobAt);
        pointed.local(SETTINGS_RELS, PLAIN_RELS);
        pointed.central(SETTINGS_RELS, oversized, blobAt);
        assertDocx(pointed.bytes(), INVALID);

        // 21 relationships parts listed in the central directory, all pointing at the same 5 MiB part.
        String big = "word/_rels/big.xml.rels";
        byte[] padded = relationshipsPart(" ".repeat(5 * 1024 * 1024 - 1024)
                + relationship(TEMPLATE, "https://example.test/t.dotm", "External"));
        var shared = handDocx();
        int bigAt = shared.local(big, padded);
        shared.central(big, padded, bigAt);
        for (int index = 1; index <= 20; index++) {
            String name = "word/_rels/part" + index + ".xml.rels";
            shared.local(name, PLAIN_RELS);
            shared.central(name, padded, bigAt);
        }
        byte[] bytes = shared.bytes();
        assertThat(bytes.length).isLessThan(CvValidator.MAX_BYTES);
        assertThat(streamedNames(bytes)).isEqualTo(centralNames(bytes));
        assertDocx(bytes, INVALID);
    }

    // Both views can list the same names in the same order while the central directory points a part at a second
    // local header hidden inside another part's data. Every .rels part is read in both views, so either way the
    // template is found.
    @Test
    void aRelationshipsPartHiddenInsideAnotherPartIsFoundInEitherView() throws Exception {
        String blob = "word/media/blob.bin";
        int hiddenHeader = 30 + blob.length();

        // Word reads the template; the local walk reads a plain part.
        var wordView = handDocx();
        byte[] inner = HandZip.localRecord(SETTINGS_RELS, REMOTE_TEMPLATE);
        int blobAt = wordView.local(blob, inner);
        wordView.central(blob, inner, blobAt);
        wordView.local(SETTINGS_RELS, PLAIN_RELS);
        wordView.central(SETTINGS_RELS, REMOTE_TEMPLATE, blobAt + hiddenHeader);
        byte[] bytes = wordView.bytes();
        assertThat(streamedNames(bytes)).isEqualTo(centralNames(bytes));
        assertDocx(bytes, ACTIVE);

        // The other way around: the local walk reads the template; Word reads a plain part.
        var streamView = handDocx();
        inner = HandZip.localRecord(SETTINGS_RELS, PLAIN_RELS);
        blobAt = streamView.local(blob, inner);
        streamView.central(blob, inner, blobAt);
        streamView.local(SETTINGS_RELS, REMOTE_TEMPLATE);
        streamView.central(SETTINGS_RELS, PLAIN_RELS, blobAt + hiddenHeader);
        bytes = streamView.bytes();
        assertThat(streamedNames(bytes)).isEqualTo(centralNames(bytes));
        assertDocx(bytes, ACTIVE);
    }

    // --- PDF ----------------------------------------------------------------------------------------------------

    @Test
    void aJavaScriptOpenActionIsRefused() throws Exception {
        assertPdf(pdf(document -> document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert(1)"))),
                ACTIVE);
    }

    @Test
    void aLaunchActionOnALinkIsRefused() throws Exception {
        assertPdf(pdf(document -> {
            var launch = new PDActionLaunch();
            launch.setFile(simpleFile("calc.exe"));
            annotate(document, link(launch));
        }), ACTIVE);
    }

    @Test
    void anEmbeddedFileInTheNamesTreeIsRefused() throws Exception {
        assertPdf(pdf(document -> {
            try {
                var file = new PDComplexFileSpecification();
                file.setFile("readme.txt");
                file.setEmbeddedFile(new PDEmbeddedFile(document,
                        new ByteArrayInputStream("hello".getBytes(StandardCharsets.US_ASCII))));
                var tree = new PDEmbeddedFilesNameTreeNode();
                tree.setNames(Map.of("readme.txt", file));
                var names = new PDDocumentNameDictionary(document.getDocumentCatalog());
                names.setEmbeddedFiles(tree);
                document.getDocumentCatalog().setNames(names);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }), ACTIVE);
    }

    // The annotation alone, without an embedded stream, is refused by its subtype.
    @Test
    void aFileAttachmentAnnotationIsRefused() throws Exception {
        assertPdf(pdf(document -> {
            var attachment = new PDAnnotationFileAttachment();
            attachment.setFile(simpleFile("readme.txt"));
            attachment.setRectangle(new PDRectangle(10, 10, 20, 20));
            annotate(document, attachment);
        }), ACTIVE);
    }

    @Test
    void theDocumentJavaScriptNameTreeIsRefused() throws Exception {
        assertPdf(pdf(document -> {
            var names = new COSDictionary();
            names.setItem(COSName.JAVA_SCRIPT, new COSDictionary());
            document.getDocumentCatalog().getCOSObject().setItem(COSName.NAMES, names);
        }), ACTIVE);
    }

    // PDFBox 3 saves objects into compressed object streams by default: the action is not visible in the raw bytes,
    // and the walk still finds it. Saved without compression (classic cross reference table), it is refused too.
    @Test
    void aJavaScriptActionInACompressedObjectStreamIsRefused() throws Exception {
        Consumer<PDDocument> script = document -> {
            var action = new PDActionJavaScript("app.alert(1)");
            annotate(document, link(action));
        };
        byte[] compressed = pdf(script);
        byte[] plain = pdf(script, CompressParameters.NO_COMPRESSION);
        assertThat(new String(plain, StandardCharsets.ISO_8859_1)).contains("/JavaScript");
        assertThat(new String(compressed, StandardCharsets.ISO_8859_1)).doesNotContain("/JavaScript", "/JS", "/Annots")
                .contains("/ObjStm");
        assertPdf(compressed, ACTIVE);
        assertPdf(plain, ACTIVE);
    }

    // Written by hand: an action whose /S is an indirect object, and a name spelled with #xx escapes, are found.
    @Test
    void indirectAndEscapedActionNamesAreFound() throws Exception {
        assertPdf(handMade(List.of("<< /S 5 0 R /F (calc.exe) >>", "/Launch"), "/OpenAction 4 0 R", -1), ACTIVE);
        assertPdf(handMade(List.of("<< /S /J#61va#53cript /JX (x) >>"), "/OpenAction 4 0 R", -1), ACTIVE);
        assertPdf(handMade(List.of("<< /S /URI /URI (https://example.test) >>"), "/OpenAction 4 0 R", -1), null);
    }

    // An object listed in the cross reference table that PDFBox cannot read is refused as invalid: a viewer that
    // rebuilds the table could still run it.
    @Test
    void anUnreadableObjectFailsClosed() throws Exception {
        byte[] broken = handMade(List.of("<< /S /JavaScript /JS (app.alert\\(1\\)) >>"), "/OpenAction 4 0 R", 4);
        assertPdf(broken, INVALID);
    }

    // A web link stays allowed, like the teammate's plain PDF and his exact 10 MiB boundary file.
    @Test
    void aUriLinkAndTheTeammatesPdfsAreAccepted() throws Exception {
        assertAccepted(pdf(document -> {
            var uri = new PDActionURI();
            uri.setURI("https://example.test/portfolio");
            annotate(document, link(uri));
        }));
        assertAccepted(CvValidatorTest.pdf(false));
        byte[] boundary = boundaryPdf();
        assertThat(boundary).hasSize(CvValidator.MAX_BYTES);
        assertAccepted(boundary);
    }

    // The walk is bounded: deeper than 64 direct levels, or more than 1 000 000 nodes, is APPLICATION_CV_INVALID.
    @Test
    void tooDeepOrTooLargeStructuresAreInvalid() throws Exception {
        assertAccepted(pdf(document -> document.getDocumentCatalog().getCOSObject()
                .setItem(COSName.getPDFName("Nested"), nested(PdfActiveContent.MAX_DEPTH - 2))));
        assertPdf(pdf(document -> document.getDocumentCatalog().getCOSObject()
                .setItem(COSName.getPDFName("Nested"), nested(PdfActiveContent.MAX_DEPTH + 5))), INVALID);
        assertPdf(pdf(document -> {
            var many = new COSArray();
            for (int index = 0; index <= PdfActiveContent.MAX_NODES; index++) {
                many.add(COSInteger.ZERO);
            }
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Many"), many);
        }), INVALID);
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private void assertDocx(byte[] bytes, String code) {
        assertCode(SECRET_NAME + ".docx", bytes, code);
    }

    private void assertPdf(byte[] bytes, String code) {
        if (code == null) {
            assertAccepted(bytes);
        } else {
            assertCode(SECRET_NAME + ".pdf", bytes, code);
        }
    }

    private void assertCode(String filename, byte[] bytes, String code) {
        assertThatThrownBy(() -> validator.validate(filename, new ByteArrayInputStream(bytes)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    var api = (ApiException) error;
                    assertThat(api.getCode()).isEqualTo(code);
                    assertThat(api.getFieldErrors()).isEmpty();
                    assertThat(api.getMessage()).doesNotContain(SECRET_NAME, "example.test", "server", "rels");
                    if (code.equals(ACTIVE)) {
                        assertThat(api.getStatus().value()).isEqualTo(400);
                        assertThat(api.getMessage()).isEqualTo(MESSAGE);
                    }
                });
    }

    private void assertAccepted(byte[] bytes) {
        boolean pdf = bytes[0] == '%';
        String name = SECRET_NAME + (pdf ? ".pdf" : ".docx");
        try {
            ValidatedCv cv = validator.validate(name, new ByteArrayInputStream(bytes));
            assertThat(cv.content()).isEqualTo(bytes);
        } catch (ApiException exception) {
            fail("expected the CV to be accepted, got " + exception.getCode());
        }
    }

    private static String relationship(String type, String target, String mode) {
        return "<Relationship Id=\"rId362\" Type=\"" + type + "\" Target=\"" + xml(target) + "\""
                + (mode == null ? "" : " TargetMode=\"" + mode + "\"") + "/>";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    // The teammate's document with one more Relationship in this .rels part (created when POI wrote none).
    private static byte[] docxWith(String relsEntry, String relationshipXml) throws Exception {
        byte[] base = CvValidatorTest.docx();
        var output = new ByteArrayOutputStream();
        boolean found = false;
        try (var input = new ZipInputStream(new ByteArrayInputStream(base)); var zip = new ZipOutputStream(output)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().equals(relsEntry)) {
                    String rels = new String(bytes, StandardCharsets.UTF_8);
                    assertThat(rels).contains("</Relationships>");
                    bytes = rels.replace("</Relationships>", relationshipXml + "</Relationships>")
                            .getBytes(StandardCharsets.UTF_8);
                    found = true;
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(bytes);
                zip.closeEntry();
            }
            if (!found) {
                zip.putNextEntry(new ZipEntry(relsEntry));
                zip.write(relationshipsPart(relationshipXml));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] relationshipsPart(String relationshipXml) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + relationshipXml + "</Relationships>").getBytes(StandardCharsets.UTF_8);
    }

    // The parts of the teammate's document, in its order, rewritten by hand. [Content_Types].xml also gets a type for
    // .bin parts, so that POI opens a package holding one.
    private static HandZip handDocx() throws Exception {
        var zip = new HandZip();
        try (var input = new ZipInputStream(new ByteArrayInputStream(CvValidatorTest.docx()))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                byte[] content = input.readAllBytes();
                if (entry.getName().equals("[Content_Types].xml")) {
                    String types = new String(content, StandardCharsets.UTF_8);
                    assertThat(types).contains("</Types>");
                    content = types.replace("</Types>", "<Default ContentType=\"application/octet-stream\""
                            + " Extension=\"bin\"/></Types>").getBytes(StandardCharsets.UTF_8);
                }
                zip.part(entry.getName(), content);
            }
        }
        return zip;
    }

    // What ZipInputStream (like POI) finds walking the local headers.
    private static List<String> streamedNames(byte[] bytes) throws Exception {
        var names = new ArrayList<String>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    // What a reader of the central directory (like Word) finds.
    private static List<String> centralNames(byte[] bytes) throws Exception {
        try (var zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            return Collections.list(zip.getEntries()).stream().map(ZipArchiveEntry::getName).toList();
        }
    }

    /**
     * A ZIP file written byte by byte with STORED parts: local() writes a local header and the data and returns its
     * offset, central() adds a central directory record that points at any offset, raw() writes bytes nothing lists.
     */
    private static final class HandZip {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final ByteArrayOutputStream directory = new ByteArrayOutputStream();
        private int records;

        static byte[] localRecord(String name, byte[] content) {
            byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            var out = new ByteArrayOutputStream();
            int32(out, 0x04034b50);
            int16(out, 20);
            int16(out, 0);
            int16(out, 0);
            int32(out, DOS_1980_01_01);
            int32(out, crc(content));
            int32(out, content.length);
            int32(out, content.length);
            int16(out, nameBytes.length);
            int16(out, 0);
            out.writeBytes(nameBytes);
            out.writeBytes(content);
            return out.toByteArray();
        }

        int local(String name, byte[] content) {
            int offset = body.size();
            body.writeBytes(localRecord(name, content));
            return offset;
        }

        // An Info-ZIP Unicode Path extra field (0x7075) giving the part with this raw name another name.
        static byte[] unicodePath(String rawName, String unicodeName) {
            byte[] utf8 = unicodeName.getBytes(StandardCharsets.UTF_8);
            var out = new ByteArrayOutputStream();
            int16(out, 0x7075);
            int16(out, 5 + utf8.length);
            out.write(1);
            int32(out, crc(rawName.getBytes(StandardCharsets.US_ASCII)));
            out.writeBytes(utf8);
            return out.toByteArray();
        }

        HandZip central(String name, byte[] content, int offset) {
            return central(name, content, offset, new byte[0]);
        }

        HandZip central(String name, byte[] content, int offset, byte[] extra) {
            byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            int32(directory, 0x02014b50);
            int16(directory, 20);
            int16(directory, 20);
            int16(directory, 0);
            int16(directory, 0);
            int32(directory, DOS_1980_01_01);
            int32(directory, crc(content));
            int32(directory, content.length);
            int32(directory, content.length);
            int16(directory, nameBytes.length);
            int16(directory, extra.length);
            int16(directory, 0);
            int16(directory, 0);
            int16(directory, 0);
            int32(directory, 0);
            int32(directory, offset);
            directory.writeBytes(nameBytes);
            directory.writeBytes(extra);
            records++;
            return this;
        }

        HandZip part(String name, byte[] content) {
            return central(name, content, local(name, content));
        }

        HandZip raw(byte[] bytes) {
            body.writeBytes(bytes);
            return this;
        }

        byte[] bytes() {
            var out = new ByteArrayOutputStream();
            out.writeBytes(body.toByteArray());
            out.writeBytes(directory.toByteArray());
            int32(out, 0x06054b50);
            int16(out, 0);
            int16(out, 0);
            int16(out, records);
            int16(out, records);
            int32(out, directory.size());
            int32(out, body.size());
            int16(out, 0);
            return out.toByteArray();
        }

        private static final int DOS_1980_01_01 = 0x0021_0000;

        private static int crc(byte[] content) {
            var crc = new CRC32();
            crc.update(content);
            return (int) crc.getValue();
        }

        private static void int16(ByteArrayOutputStream out, int value) {
            out.write(value & 0xFF);
            out.write((value >>> 8) & 0xFF);
        }

        private static void int32(ByteArrayOutputStream out, int value) {
            int16(out, value & 0xFFFF);
            int16(out, value >>> 16);
        }
    }

    private static byte[] withEntry(byte[] docx, String name, byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(docx)); var zip = new ZipOutputStream(output)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(bytes);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static byte[] pdf(Consumer<PDDocument> change) throws Exception {
        return pdf(change, CompressParameters.DEFAULT_COMPRESSION);
    }

    private static byte[] pdf(Consumer<PDDocument> change, CompressParameters compression) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            change.accept(document);
            document.save(output, compression);
            byte[] bytes = output.toByteArray();
            // The fixture is a well-formed PDF on its own (PDFBox reads it back).
            try (var check = Loader.loadPDF(bytes)) {
                assertThat(check.getNumberOfPages()).isEqualTo(1);
            }
            return bytes;
        }
    }

    private static PDSimpleFileSpecification simpleFile(String name) {
        var file = new PDSimpleFileSpecification();
        file.setFile(name);
        return file;
    }

    private static PDAnnotationLink link(org.apache.pdfbox.pdmodel.interactive.action.PDAction action) {
        var link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(10, 10, 100, 20));
        link.setAction(action);
        return link;
    }

    private static void annotate(PDDocument document,
                                 org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation annotation) {
        try {
            PDPage page = document.getPage(0);
            var annotations = new ArrayList<>(page.getAnnotations());
            annotations.add(annotation);
            page.setAnnotations(annotations);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static COSArray nested(int levels) {
        var outer = new COSArray();
        var current = outer;
        for (int level = 1; level < levels; level++) {
            var inner = new COSArray();
            current.add(inner);
            current = inner;
        }
        current.add(COSInteger.ONE);
        return outer;
    }

    // A classic PDF written by hand: catalog (1), pages (2), page (3), then the extra objects from 4 on. The catalog
    // gets catalogExtra. brokenObject (or -1) gets a cross reference offset that points at the wrong place.
    private static byte[] handMade(List<String> extra, String catalogExtra, int brokenObject) {
        List<String> objects = new ArrayList<>(List.of("<< /Type /Catalog /Pages 2 0 R " + catalogExtra + " >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"));
        objects.addAll(extra);
        var pdf = new StringBuilder("%PDF-1.7\n");
        long[] offsets = new long[objects.size() + 1];
        for (int index = 0; index < objects.size(); index++) {
            offsets[index + 1] = pdf.length();
            pdf.append(index + 1).append(" 0 obj\n").append(objects.get(index)).append("\nendobj\n");
        }
        if (brokenObject > 0) {
            offsets[brokenObject] = offsets[1] + 3;
        }
        long xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int number = 1; number <= objects.size(); number++) {
            pdf.append(String.format("%010d 00000 n \n", offsets[number]));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    // The teammate's boundary fixture (CvValidatorTest): padding inside a real content stream, exactly 10 MiB.
    private static byte[] boundaryPdf() throws Exception {
        int payload = CvValidator.MAX_BYTES - 1000;
        byte[] boundary = withPayload(payload);
        for (int attempt = 0; boundary.length != CvValidator.MAX_BYTES && attempt < 5; attempt++) {
            payload += CvValidator.MAX_BYTES - boundary.length;
            boundary = withPayload(payload);
        }
        return boundary;
    }

    private static byte[] withPayload(int size) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            var contents = new PDStream(document);
            page.setContents(contents);
            try (var stream = contents.createOutputStream()) {
                byte[] spaces = new byte[8192];
                Arrays.fill(spaces, (byte) ' ');
                for (int left = size; left > 0; left -= Math.min(left, spaces.length)) {
                    stream.write(spaces, 0, Math.min(left, spaces.length));
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
