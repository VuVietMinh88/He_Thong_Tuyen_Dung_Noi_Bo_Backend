package vn.ttcs.recruitment.application;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task 362 (story S4-09): does a parsed PDF hold content a viewer runs, opens or drops on the reader's machine? Used by
 * CvValidator after the structural checks, so the public intake and referrals refuse such a CV before it is stored and
 * before any staff member opens it (GET /api/v1/applications/{id}/cv).
 * <p>
 * Every indirect object of the document, compressed object streams included (PDFBox resolves them through the cross
 * reference table and parses each object stream once), and the trailer are walked with their direct dictionaries and
 * arrays. An indirect reference inside them is not followed: it is walked as its own object. Stream data is never
 * decoded; only a stream's dictionary is looked at. Active content is: an action with /S /Launch or /S /JavaScript;
 * any dictionary with a /JS, /JavaScript (the document's JavaScript name tree), /EmbeddedFiles or /EF key; a
 * dictionary with /Type /EmbeddedFile or /Subtype /FileAttachment. /URI links stay allowed. JavaScript is refused
 * until BA/PO answer question 25(11).
 * <p>
 * The walk is bounded and fails closed: more than 64 nested direct levels, more than 1 000 000 visited nodes, or an
 * object of the cross reference table that PDFBox cannot read fail as an IOException, which CvValidator answers as
 * APPLICATION_CV_INVALID.
 */
final class PdfActiveContent {
    static final int MAX_DEPTH = 64;
    static final int MAX_NODES = 1_000_000;
    private static final COSName LAUNCH = COSName.getPDFName("Launch");
    private static final COSName FILE_ATTACHMENT = COSName.getPDFName("FileAttachment");
    // Keys that only ever hold scripts or attached files.
    private static final Set<COSName> ACTIVE_KEYS = Set.of(COSName.JS, COSName.JAVA_SCRIPT, COSName.EMBEDDED_FILES,
            COSName.EF);

    private int nodes;

    private PdfActiveContent() { }

    // True as soon as one object holds active content. Throws IOException when the structure is too deep or too large,
    // or when PDFBox cannot read one of the objects listed in the cross reference table.
    static boolean found(COSDocument document) throws IOException {
        var walk = new PdfActiveContent();
        if (walk.visit(document.getTrailer(), 0)) {
            return true;
        }
        // A copy: resolving an object must not change the set being iterated.
        for (COSObjectKey key : List.copyOf(document.getXrefTable().keySet())) {
            COSBase object = document.getObjectFromPool(key).getObject();
            // PDFBox logs an object it cannot read and gives null (a literal null object is COSNull). A lenient viewer
            // may still read it, for example by rebuilding a broken cross reference table, so the walk fails closed.
            if (object == null) {
                throw new IOException("Unreadable PDF object");
            }
            if (walk.visit(object, 0)) {
                return true;
            }
        }
        return false;
    }

    private boolean visit(COSBase node, int depth) throws IOException {
        if (++nodes > MAX_NODES || depth > MAX_DEPTH) {
            throw new IOException("PDF structure limit");
        }
        // A COSStream is a COSDictionary: its dictionary is checked, its data never read.
        if (node instanceof COSDictionary dictionary) {
            if (active(dictionary)) {
                return true;
            }
            // Raw values: an indirect reference stays a COSObject (a leaf here) and is visited as its own object.
            for (Map.Entry<COSName, COSBase> entry : dictionary.entrySet()) {
                if (visit(entry.getValue(), depth + 1)) {
                    return true;
                }
            }
        } else if (node instanceof COSArray array) {
            for (int index = 0; index < array.size(); index++) {
                if (visit(array.get(index), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean active(COSDictionary dictionary) {
        for (COSName key : dictionary.keySet()) {
            if (ACTIVE_KEYS.contains(key)) {
                return true;
            }
        }
        // getDictionaryObject resolves an indirect value, so /S 12 0 R naming /Launch is found too.
        COSBase action = dictionary.getDictionaryObject(COSName.S);
        return LAUNCH.equals(action) || COSName.JAVA_SCRIPT.equals(action)
                || COSName.EMBEDDED_FILE.equals(dictionary.getDictionaryObject(COSName.TYPE))
                || FILE_ATTACHMENT.equals(dictionary.getDictionaryObject(COSName.SUBTYPE));
    }
}
