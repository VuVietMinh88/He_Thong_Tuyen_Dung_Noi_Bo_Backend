package vn.ttcs.recruitment.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimePart;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Reads the parts of a received email in tests: the text/plain and text/html bodies, the leaf parts, and the shape of
 * the MIME tree. It accepts a single-part text email too, so a test does not depend on whether a message is
 * multipart.
 */
public final class MailParts {

    private MailParts() {
    }

    // The text/plain body of the message.
    public static String text(Part message) throws MessagingException, IOException {
        return onlyBody(message, "text/plain");
    }

    // The text/html body of the message.
    public static String html(Part message) throws MessagingException, IOException {
        return onlyBody(message, "text/html");
    }

    // Every part that is not itself a multipart, depth first, in message order.
    public static List<Part> leaves(Part message) throws MessagingException, IOException {
        List<Part> leaves = new ArrayList<>();
        collect(message, leaves);
        return leaves;
    }

    // The one part with the Content-ID <contentId>, such as an inline image that the HTML shows as cid:contentId.
    public static MimePart inline(Part message, String contentId) throws MessagingException, IOException {
        List<MimePart> found = new ArrayList<>();
        for (Part leaf : leaves(message)) {
            if (leaf instanceof MimePart part && ("<" + contentId + ">").equals(part.getContentID())) {
                found.add(part);
            }
        }
        if (found.size() != 1) {
            throw new AssertionError("Expected one part <" + contentId + "> but found " + found.size() + " in "
                    + structure(message));
        }
        return found.getFirst();
    }

    // The MIME tree as base types, for example
    // "multipart/mixed[multipart/related[multipart/alternative[text/plain,text/html],image/png]]".
    public static String structure(Part part) throws MessagingException, IOException {
        String type = baseType(part);
        if (!(part.getContent() instanceof Multipart multipart)) {
            return type;
        }
        StringJoiner children = new StringJoiner(",", type + "[", "]");
        for (int index = 0; index < multipart.getCount(); index++) {
            children.add(structure(multipart.getBodyPart(index)));
        }
        return children.toString();
    }

    public static String baseType(Part part) throws MessagingException {
        return new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
    }

    private static String onlyBody(Part message, String type) throws MessagingException, IOException {
        List<Part> bodies = new ArrayList<>();
        for (Part leaf : leaves(message)) {
            if (leaf.isMimeType(type) && !Part.ATTACHMENT.equalsIgnoreCase(leaf.getDisposition())) {
                bodies.add(leaf);
            }
        }
        if (bodies.size() != 1) {
            throw new AssertionError("Expected one " + type + " part but found " + bodies.size() + " in "
                    + structure(message));
        }
        return (String) bodies.getFirst().getContent();
    }

    private static void collect(Part part, List<Part> leaves) throws MessagingException, IOException {
        if (part.getContent() instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                collect(multipart.getBodyPart(index), leaves);
            }
        } else {
            leaves.add(part);
        }
    }
}
