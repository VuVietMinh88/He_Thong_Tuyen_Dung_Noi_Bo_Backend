package vn.ttcs.recruitment.cvextraction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Synthetic benchmark data only; run manually inside the OCR image, never consumes real CVs. */
public final class CvExtractionFixtureGenerator {
    private static final Path FONT = Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
    private static final String VI = "Họ và tên: Nguyễn Văn An\nEmail: an.nguyen@example.test\nĐiện thoại: 0912 345 678";
    private static final String EN = "Full name: Alice Nguyen\nEmail: alice@example.test\nPhone: +84 912 345 678";
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        write(directory, "vi-text", new String[]{VI}, new boolean[]{false});
        write(directory, "en-text", new String[]{EN}, new boolean[]{false});
        write(directory, "vi-scan", new String[]{VI}, new boolean[]{true});
        write(directory, "en-scan", new String[]{EN}, new boolean[]{true});
        write(directory, "mixed", new String[]{"Professional experience with Java and relational databases in enterprise software development.", VI}, new boolean[]{false, true});
        write(directory, "reference", new String[]{EN + "\nReferences\nFull name: Bob Nguyen\nEmail: bob@example.test\nPhone: 0987654321"}, new boolean[]{false});
        write(directory, "ambiguous", new String[]{"Full name: Alice Nguyen\nFull name: Bob Nguyen\nEmail: alice@example.test\nEmail: bob@example.test"}, new boolean[]{false});
        write(directory, "blank", new String[]{""}, new boolean[]{false});
        write(directory, "six-pages", new String[]{"", "", "", "", "", EN}, new boolean[]{false, false, false, false, false, false});
        try (var doc = new PDDocument()) {
            PDPage page = page(doc, VI, true);
            try (var content = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                text(doc, content, "Professional experience with Java and relational databases in enterprise software development.", 50, 250);
            }
            doc.save(directory.resolve("same-page-mixed.pdf").toFile());
        }
        try (var doc = new PDDocument()) {
            var page = new PDPage(); doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                text(doc, content, "Full name: Alice Nguyen\nEmail: alice@example.test\nPhone: 0912345678", 40, 750);
                text(doc, content, "Work Experience\nJava developer\nEducation\nUniversity studies", 320, 750);
            }
            doc.save(directory.resolve("two-columns.pdf").toFile());
        }
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(10000, 10000)));
            doc.save(directory.resolve("huge-page.pdf").toFile());
        }
        try (var doc = new PDDocument()) {
            page(doc, EN, false);
            doc.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            doc.save(directory.resolve("encrypted.pdf").toFile());
        }
        Files.writeString(directory.resolve("broken.pdf"), "%PDF-1.7\nprivate fake data");
    }
    private static void write(Path directory, String name, String[] texts, boolean[] scans) throws Exception {
        try (var doc = new PDDocument()) {
            for (int index = 0; index < texts.length; index++) page(doc, texts[index], scans[index]);
            doc.save(directory.resolve(name + ".pdf").toFile());
        }
    }
    private static PDPage page(PDDocument doc, String text, boolean scan) throws Exception {
        var page = new PDPage(PDRectangle.A4); doc.addPage(page);
        try (var content = new PDPageContentStream(doc, page)) {
            if (scan) {
                var image = new BufferedImage(2480, 3508, BufferedImage.TYPE_BYTE_GRAY);
                var graphics = image.createGraphics();
                try {
                    graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                    graphics.setColor(Color.BLACK);
                    graphics.setFont(Font.createFont(Font.TRUETYPE_FONT, FONT.toFile()).deriveFont(46f));
                    int y = 240;
                    for (String line : text.split("\n")) { graphics.drawString(line, 150, y); y += 90; }
                } finally { graphics.dispose(); }
                content.drawImage(LosslessFactory.createFromImage(doc, image), 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                image.flush();
            } else text(doc, content, text, 40, 750);
        }
        return page;
    }
    private static void text(PDDocument doc, PDPageContentStream content, String text, int x, int y) throws Exception {
        content.beginText(); content.setFont(PDType0Font.load(doc, FONT.toFile()), 11); content.setLeading(20);
        content.newLineAtOffset(x, y);
        for (String line : text.split("\n")) { content.showText(line); content.newLine(); }
        content.endText();
    }
}
