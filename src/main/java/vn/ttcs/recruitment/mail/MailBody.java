package vn.ttcs.recruitment.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The content of an account email (password reset, account activation) as a list of blocks. MailLayout renders the
 * same blocks twice, as the text/plain part and as the text/html part of one message, and adds the support footer.
 * Values stay raw here: MailLayout escapes them for HTML, so a block never carries markup.
 *
 * <p>In the plain text the blocks are separated by an empty line, except that consecutive fields stay on consecutive
 * lines and a link's URL comes on the line right after the paragraph before it, which introduces the link. That keeps
 * the plain text of the existing emails line for line.
 */
public record MailBody(List<Block> blocks) {

    public MailBody {
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("An email needs at least one block.");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public sealed interface Block permits Paragraph, Field, Link {
    }

    // A sentence or paragraph of text.
    public record Paragraph(String text) implements Block {
        public Paragraph {
            Objects.requireNonNull(text, "text");
        }
    }

    // A labelled value the reader may need to copy, such as the login email or the temporary password.
    public record Field(String label, String value) implements Block {
        public Field {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }
    }

    // A button in the HTML part, with the URL also written out below it; only the URL in the plain text.
    // Only http and https URLs are accepted, so a link can never run a script in a mail client.
    public record Link(String label, String url) implements Block {
        public Link {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(url, "url");
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw new IllegalArgumentException("An email link must be an http or https URL.");
            }
        }
    }

    public static final class Builder {
        private final List<Block> blocks = new ArrayList<>();

        private Builder() {
        }

        public Builder paragraph(String text) {
            blocks.add(new Paragraph(text));
            return this;
        }

        public Builder field(String label, String value) {
            blocks.add(new Field(label, value));
            return this;
        }

        public Builder link(String label, String url) {
            blocks.add(new Link(label, url));
            return this;
        }

        public MailBody build() {
            return new MailBody(blocks);
        }
    }
}
