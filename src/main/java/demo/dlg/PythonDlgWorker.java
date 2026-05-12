package demo.dlg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import demo.dlg.model.DlgWorkerRequest;
import demo.dlg.model.DlgWorkerUpdate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PythonDlgWorker implements DlgWorker {
    private final String pythonCommand;
    private final Path workerScript;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public PythonDlgWorker(
            @Value("${dlg.python-command:python}") String pythonCommand,
            @Value("${dlg.worker-script:scripts/dlg_worker.py}") Path workerScript
    ) {
        this.pythonCommand = pythonCommand;
        this.workerScript = workerScript;
    }

    @Override
    public void run(DlgWorkerRequest request, Consumer<DlgWorkerUpdate> updates) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                pythonCommand,
                workerScript.toString(),
                "--job-dir", request.jobDir().toString(),
                "--sigma", Double.toString(request.sigma()),
                "--iterations", Integer.toString(request.iterations()),
                "--frame-every", Integer.toString(request.frameEvery()),
                "--seed", Long.toString(request.seed()),
                "--lbfgs-max-iter", "4"
        ));

        if (request.targetImage() != null) {
            command.add("--image");
            command.add(request.targetImage().toString());
        } else {
            command.add("--sample");
            command.add(request.sampleId());
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, updates);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Python DLG worker exited with code " + exitCode);
        }
    }

    private void parseLine(String line, Consumer<DlgWorkerUpdate> updates) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) {
            if (!trimmed.isBlank()) {
                updates.accept(new DlgWorkerUpdate("message", null, null, null, null, null, trimmed));
            }
            return;
        }

        try {
            JsonNode node = jsonMapper.readTree(trimmed);
            updates.accept(new DlgWorkerUpdate(
                    text(node, "type", "message"),
                    integer(node, "iteration"),
                    number(node, "loss"),
                    number(node, "mse"),
                    number(node, "psnr"),
                    text(node, "frame", null),
                    text(node, "message", null)
            ));
        } catch (Exception e) {
            updates.accept(new DlgWorkerUpdate("message", null, null, null, null, null, trimmed));
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }
}
