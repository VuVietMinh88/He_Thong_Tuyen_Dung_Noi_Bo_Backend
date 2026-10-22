package vn.ttcs.recruitment.cvsuggestion;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CvSandboxProcessTest {
    @TempDir Path root;
    private static final String IMAGE="sha256:"+"a".repeat(64);
    @Test void boundedProcessReturnsResultAndRemovesPrivateFile() throws Exception {
        Path script=source("System.out.print(\""+CvSuggestionJson.unsupported().replace("\"","\\\"")+"\");");
        var adapter=adapter(script,30);
        assertThat(adapter.extract(new byte[]{1}).outcome().name()).isEqualTo("UNSUPPORTED");
        try (var files=Files.list(root.resolve("private"))) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void timeoutDoesNotWaitForStdoutReaderAndCleansFiles() throws Exception {
        var adapter=adapter(source("Thread.sleep(30000);"),1);
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(()->
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(8),()->adapter.extract(new byte[]{1})))
                .withMessage("SANDBOX_TIMEOUT");
        try (var files=Files.list(root.resolve("private"))) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void oversizedOutputIsRejectedAndFilesAreRemoved() throws Exception {
        var adapter=adapter(source("System.out.print(\"x\".repeat(150000));"),30);
        assertThatThrownBy(()->adapter.extract(new byte[]{1})).isInstanceOf(java.io.IOException.class);
        try (var files=Files.list(root.resolve("private"))) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void malformedUtf8CannotBecomeAcceptedCandidateText() throws Exception {
        String value=CvSuggestionJson.unsupported().replace("PDF_REQUIRED","BAD_@_BYTE");
        Path script=source("byte[] value=\""+value.replace("\"","\\\"")+"\".getBytes(java.nio.charset.StandardCharsets.UTF_8);"
                +"for(int i=0;i<value.length;i++) if(value[i]==64) value[i]=(byte)255;System.out.write(value);");
        assertThatThrownBy(()->adapter(script,30).extract(new byte[]{1})).isInstanceOf(java.nio.charset.CharacterCodingException.class);
        try (var files=Files.list(root.resolve("private"))) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void scavengerRemovesOnlyStaleFixedFilesAndRejectsMutableImage() throws Exception {
        var adapter=adapter(source(""),30);
        Path stale=Files.createDirectory(root.resolve("private/cv-worker-old"));
        Files.writeString(stale.resolve("cv.pdf"),"private");
        Files.setLastModifiedTime(stale,java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(700)));
        Path fresh=Files.createDirectory(root.resolve("private/cv-worker-fresh"));
        adapter.cleanupAbandoned();assertThat(stale).doesNotExist();assertThat(fresh).exists();
        assertThatThrownBy(()->new CvSandboxProcess("python",root.resolve("FakeRunner.java"),"local:latest",root))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private Path source(String body) throws Exception {
        return Files.writeString(root.resolve("FakeRunner.java"),"public class FakeRunner { public static void main(String[] args) throws Exception { "+body+" } }");
    }
    private CvSandboxProcess adapter(Path script,int seconds) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new CvSandboxProcess(java,script,IMAGE,root.resolve("private"),seconds);
    }
}
