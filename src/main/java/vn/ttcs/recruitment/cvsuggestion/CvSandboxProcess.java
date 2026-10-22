package vn.ttcs.recruitment.cvsuggestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import vn.ttcs.recruitment.cvextraction.CvExtractionResult;

/** Used only by the standalone worker. The web process never constructs or calls this adapter. */
public final class CvSandboxProcess {
    private final String python,image;
    private final Path runner,temporaryRoot;
    private final int timeoutSeconds;
    public CvSandboxProcess(String python,Path runner,String image,Path temporaryRoot) throws IOException {
        this(python,runner,image,temporaryRoot,150);
    }
    CvSandboxProcess(String python,Path runner,String image,Path temporaryRoot,int timeoutSeconds) throws IOException {
        if (python.isBlank() || !Files.isRegularFile(runner) || !image.matches("(?:[a-zA-Z0-9._:/-]+@)?sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Worker requires a runner and immutable image ID/digest");
        this.python=python;this.runner=runner.toAbsolutePath().normalize();this.image=image;
        if (timeoutSeconds<1 || timeoutSeconds>150) throw new IllegalArgumentException("Invalid process deadline");
        this.timeoutSeconds=timeoutSeconds;
        this.temporaryRoot=temporaryRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.temporaryRoot);
        if (Files.isSymbolicLink(this.temporaryRoot)) throw new IOException("Invalid worker directory");
        restrict(this.temporaryRoot,true);
    }
    public CvExtractionResult extract(byte[] bytes) throws IOException,InterruptedException {
        Path directory=Files.createTempDirectory(temporaryRoot,"cv-worker-");
        restrict(directory,true);
        Path pdf=directory.resolve("cv.pdf");
        Process process=null;
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        try {
            Files.write(pdf,bytes,StandardOpenOption.CREATE_NEW);restrict(pdf,false);
            var command=new ProcessBuilder(python,runner.toString(),pdf.toString(),"--image",image);
            // Never pass database/SMTP/JWT credentials to Python, Docker or the extraction container.
            var environment=command.environment();
            Set<String> allowed=Set.of("PATH","HOME","USERPROFILE","SYSTEMROOT","WINDIR","TEMP","TMP",
                    "DOCKER_HOST","DOCKER_CONTEXT","DOCKER_TLS_VERIFY","DOCKER_CERT_PATH","DOCKER_CONFIG");
            environment.keySet().removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
            // Pipes on Windows otherwise use the legacy OS code page and may fail on Vietnamese names.
            environment.put("PYTHONIOENCODING","utf-8");environment.put("PYTHONUTF8","1");
            command.redirectError(ProcessBuilder.Redirect.DISCARD);
            process=command.start();
            process.getOutputStream().close();
            Process child=process;
            Future<byte[]> output=executor.submit(() -> {
                byte[] data=child.getInputStream().readNBytes(131073);
                if (data.length>131072) { child.destroyForcibly();throw new IOException("OUTPUT_LIMIT"); }
                return data;
            });
            if (!process.waitFor(timeoutSeconds,TimeUnit.SECONDS)) throw new IOException("SANDBOX_TIMEOUT");
            byte[] data;
            try { data=output.get(3,TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException exception) { throw new IOException("INVALID_RESULT"); }
            if (process.exitValue()!=0) throw new IOException("SANDBOX_FAILED");
            return CvSuggestionJson.read(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data)).toString());
        } finally {
            try {
                if (process!=null) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();process.getInputStream().close();
                }
            } finally {
                executor.shutdownNow();
                try { Files.deleteIfExists(pdf); } finally { Files.deleteIfExists(directory); }
            }
        }
    }
    /** Only fixed files in this worker's private directory; never recursive deletion or following symlinks. */
    public void cleanupAbandoned() throws IOException {
        try (var directories=Files.newDirectoryStream(temporaryRoot,"cv-worker-*")) {
            for (Path item : directories) {
                Path directory=item.toAbsolutePath().normalize();
                if (!directory.startsWith(temporaryRoot) || Files.isSymbolicLink(directory)
                        || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)
                        || Files.getLastModifiedTime(directory).toInstant().isAfter(Instant.now().minusSeconds(600))) continue;
                Path pdf=directory.resolve("cv.pdf");
                if (Files.isSymbolicLink(pdf)) continue;
                Files.deleteIfExists(pdf);
                try { Files.deleteIfExists(directory); } catch (DirectoryNotEmptyException ignored) { /* Not ours to remove. */ }
            }
        }
    }
    private static void restrict(Path path,boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        // On Windows the operator must give this worker account an exclusive ACL (documented setup).
    }
}
