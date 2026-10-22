package vn.ttcs.recruitment.cvextraction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Parses bounded TSV into transient text/layout. Confidence is not exposed as a probability. */
final class TesseractTsvParser {
    private static final String HEADER = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";

    static OcrEngine.Page parse(String tsv, int width, int height) throws IOException {
        if (width < 1 || height < 1) throw new TesseractOcrEngine.Failure("OCR_FAILED");
        var lines = new LinkedHashMap<String, Line>();
        int characters = 0;
        try (var reader = new BufferedReader(new StringReader(tsv))) {
            if (!HEADER.equals(reader.readLine())) throw new TesseractOcrEngine.Failure("OCR_FAILED");
            String row;
            while ((row = reader.readLine()) != null) {
                if (row.isBlank()) continue;
                if (row.length() > CvExtractionLimits.MAX_LINE + 256) throw new TesseractOcrEngine.Failure("TEXT_LIMIT");
                String[] cells = row.split("\t", 12);
                if (cells.length != 12) throw new TesseractOcrEngine.Failure("OCR_FAILED");
                int level = Integer.parseInt(cells[0]);
                if (level < 1 || level > 5) throw new TesseractOcrEngine.Failure("OCR_FAILED");
                if (level != 5 || cells[11].isBlank()) continue;
                for (int index = 1; index <= 5; index++) if (Integer.parseInt(cells[index]) < 1) throw new TesseractOcrEngine.Failure("OCR_FAILED");
                double confidence = Double.parseDouble(cells[10]);
                if (!Double.isFinite(confidence) || confidence < 0 || confidence > 100) throw new TesseractOcrEngine.Failure("OCR_FAILED");
                int left = Integer.parseInt(cells[6]), top = Integer.parseInt(cells[7]);
                int boxWidth = Integer.parseInt(cells[8]), boxHeight = Integer.parseInt(cells[9]);
                if (left < 0 || top < 0 || boxWidth <= 0 || boxHeight <= 0
                        || (long) left + boxWidth > width || (long) top + boxHeight > height) throw new TesseractOcrEngine.Failure("OCR_FAILED");
                characters += cells[11].length() + 1;
                if (characters > CvExtractionLimits.MAX_TEXT) throw new TesseractOcrEngine.Failure("TEXT_LIMIT");
                String key = String.join(":", cells[1], cells[2], cells[3], cells[4]);
                if (!lines.containsKey(key) && lines.size() >= CvExtractionLimits.MAX_OCR_LINES) throw new TesseractOcrEngine.Failure("TEXT_LIMIT");
                Line line = lines.computeIfAbsent(key, ignored -> new Line());
                if (!line.text.isEmpty()) line.text.append(' ');
                line.text.append(cells[11]);
                if (line.text.length() > CvExtractionLimits.MAX_LINE) throw new TesseractOcrEngine.Failure("TEXT_LIMIT");
                line.left = Math.min(line.left, left); line.top = Math.min(line.top, top);
                line.bottom = Math.max(line.bottom, top + boxHeight);
            }
        } catch (NumberFormatException exception) { throw new TesseractOcrEngine.Failure("OCR_FAILED"); }
        // Read top-to-bottom across blocks, so a full left sidebar cannot precede a header on the right.
        var ordered = lines.values().stream().sorted(Comparator.comparingInt((Line line) -> line.top).thenComparingInt(line -> line.left)).toList();
        var text = new StringBuilder();
        var positions = new ArrayList<OcrEngine.LinePosition>();
        for (Line line : ordered) {
            if (!text.isEmpty()) text.append('\n');
            positions.add(new OcrEngine.LinePosition(positions.size(), line.left / (double) width,
                    line.top / (double) height, (line.bottom - line.top) / (double) height));
            text.append(line.text);
        }
        return new OcrEngine.Page(text.toString(), List.copyOf(positions));
    }

    private static final class Line {
        final StringBuilder text = new StringBuilder();
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, bottom;
    }
}
