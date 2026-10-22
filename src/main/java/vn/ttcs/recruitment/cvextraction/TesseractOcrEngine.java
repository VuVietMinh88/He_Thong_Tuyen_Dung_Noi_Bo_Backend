package vn.ttcs.recruitment.cvextraction;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Runs only inside the extraction sandbox. No shell interpolation, inherited stderr, or unbounded output. */
public final class TesseractOcrEngine implements OcrEngine {
    @Override public String recognize(BufferedImage image) throws IOException {
        return recognizePage(image).text();
    }

    @Override public Page recognizePage(BufferedImage image) throws IOException {
        if ((long) image.getWidth() * image.getHeight() > CvExtractionLimits.MAX_PIXELS) throw new Failure("IMAGE_LIMIT");
        Path directory = Files.createTempDirectory("cv-ocr-");
        Path input = directory.resolve("page.png");
        Process process = null;
        FutureTask<byte[]> output = null;
        try {
            if (!ImageIO.write(image, "png", input.toFile())) throw new Failure("OCR_UNAVAILABLE");
            process = new ProcessBuilder("/usr/bin/tesseract", input.toString(), "stdout", "-l", "vie+eng",
                    "--oem", "1", "--psm", "3", "quiet", "tsv")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            Process running = process;
            output = new FutureTask<>(() -> {
                try (var stream = running.getInputStream()) {
                    byte[] bytes = stream.readNBytes(CvExtractionLimits.MAX_OCR_OUTPUT_BYTES + 1);
                    if (bytes.length > CvExtractionLimits.MAX_OCR_OUTPUT_BYTES) {
                        running.destroyForcibly(); throw new Failure("TEXT_LIMIT");
                    }
                    return bytes;
                }
            });
            Thread.ofVirtual().name("cv-ocr-output").start(output);
            if (!process.waitFor(CvExtractionLimits.OCR_SECONDS, TimeUnit.SECONDS)) throw new Failure("OCR_TIMEOUT");
            if (process.exitValue() != 0) throw new Failure("OCR_FAILED");
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return TesseractTsvParser.parse(text, image.getWidth(), image.getHeight());
        } catch (Failure exception) { throw exception; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new Failure("OCR_INTERRUPTED"); }
        catch (CharacterCodingException exception) { throw new Failure("OCR_FAILED"); }
        catch (Exception exception) { throw new Failure("OCR_UNAVAILABLE"); }
        finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            if (output != null) output.cancel(true);
            try { Files.deleteIfExists(input); } finally { Files.deleteIfExists(directory); }
        }
    }
    static final class Failure extends IOException {
        private final String code;
        Failure(String code) { super("CV OCR operation failed"); this.code = code; }
        String code() { return code; }
    }
}
