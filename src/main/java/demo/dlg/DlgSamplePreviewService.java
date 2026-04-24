package demo.dlg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DlgSamplePreviewService {
    private final String pythonCommand;
    private final Path workerScript;
    private final Path previewRoot;

    public DlgSamplePreviewService(
            @Value("${dlg.python-command:python}") String pythonCommand,
            @Value("${dlg.worker-script:scripts/dlg_worker.py}") Path workerScript,
            @Value("${dlg.preview-dir:var/dlg/previews}") Path previewRoot
    ) {
        this.pythonCommand = pythonCommand;
        this.workerScript = workerScript;
        this.previewRoot = previewRoot.toAbsolutePath().normalize();
    }

    public Path previewPath(String sampleId) throws IOException, InterruptedException {
        validateSampleId(sampleId);
        Path previewDir = previewRoot.resolve(safeName(sampleId)).normalize();
        Path target = previewDir.resolve("target.png");
        if (Files.exists(target) && isFresh(target)) {
            return target;
        }

        Files.createDirectories(previewDir);
        List<String> command = List.of(
                pythonCommand,
                workerScript.toString(),
                "--job-dir",
                previewDir.toString(),
                "--sample",
                sampleId,
                "--preview-only",
                "--device",
                "cpu"
        );

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = String.join("\n", reader.lines().toList());
        }

        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IOException("Timed out while generating sample preview.");
        }
        if (process.exitValue() != 0 || !Files.exists(target)) {
            throw new IOException("Could not generate sample preview. " + output);
        }
        return target;
    }

    private static boolean isFresh(Path target) throws IOException {
        Instant modified = Files.getLastModifiedTime(target).toInstant();
        return modified.isAfter(Instant.now().minus(Duration.ofHours(12)));
    }

    private static void validateSampleId(String sampleId) {
        if (sampleId == null || !sampleId.matches("[A-Za-z0-9:_-]{1,40}")) {
            throw new IllegalArgumentException("Sample id contains unsupported characters.");
        }
    }

    private static String safeName(String sampleId) {
        return sampleId.replace(':', '_');
    }
}
