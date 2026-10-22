package vn.ttcs.recruitment.cvextraction;

import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;

/** One local PDF in, one bounded JSON result out. Does not start Spring or load application configuration. */
public final class CvExtractionCommand {
    private CvExtractionCommand() { }
    public static void main(String[] args) throws Exception {
        CvExtractionResult result;
        if (args.length != 1 || !"/input/cv.pdf".equals(args[0])) result = CvExtractionResult.failure("INPUT_INVALID");
        else {
            try (var input = Files.newInputStream(Path.of(args[0]))) {
                byte[] bytes = input.readNBytes(CvExtractionLimits.MAX_BYTES + 1);
                result = new PdfCvExtractor(new TesseractOcrEngine()).extract(bytes);
            } catch (Exception exception) { result = CvExtractionResult.failure("EXTRACTION_FAILED"); }
        }
        System.out.println(JsonMapper.builder().build().writeValueAsString(result));
    }
}
