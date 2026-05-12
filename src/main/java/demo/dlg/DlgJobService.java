package demo.dlg;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import demo.dlg.model.DlgCreateJobCommand;
import demo.dlg.model.DlgJobParameters;
import demo.dlg.model.DlgJobSnapshot;
import demo.dlg.model.DlgWorkerRequest;
import demo.dlg.model.DlgWorkerUpdate;

@Service
public class DlgJobService {
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    private final DlgWorker worker;
    private final Path jobsRoot;
    private final ExecutorService executor;
    private final Map<String, DlgJob> jobs = new ConcurrentHashMap<>();
    private DlgJob activeJob;

    public DlgJobService(
            DlgWorker worker,
            @Value("${dlg.jobs-dir:var/dlg/jobs}") Path jobsRoot
    ) {
        this.worker = worker;
        this.jobsRoot = jobsRoot.toAbsolutePath().normalize();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "dlg-worker");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    public synchronized DlgJobSnapshot createJob(DlgCreateJobCommand command) throws IOException {
        validate(command);
        if (activeJob != null && !activeJob.isTerminal()) {
            throw new IllegalStateException("A DLG job is already running. Wait for it to finish before starting another.");
        }

        String id = UUID.randomUUID().toString();
        Files.createDirectories(jobsRoot);
        Path jobDir = jobsRoot.resolve(id).normalize();
        Files.createDirectories(jobDir.resolve("frames"));

        Path targetImage = null;
        if (command.hasImage()) {
            targetImage = jobDir.resolve("target.png");
            writeNormalizedImage(command.imageBytes(), targetImage);
        }

        DlgJobParameters parameters = new DlgJobParameters(
                command.hasImage() ? "upload" : command.sampleId(),
                command.sigma(),
                command.iterations(),
                command.frameEvery(),
                command.seed(),
                command.hasImage()
        );
        DlgJob job = new DlgJob(id, parameters, jobDir, targetImage);
        jobs.put(id, job);
        activeJob = job;
        executor.submit(() -> runJob(job));
        return job.snapshot();
    }

    public DlgJobSnapshot getSnapshot(String id) {
        return getJob(id).snapshot();
    }

    public SseEmitter subscribe(String id) {
        DlgJob job = getJob(id);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        job.addEmitter(emitter);
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(job.snapshot()));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public Path targetPath(String id) {
        return getJob(id).jobDir().resolve("target.png");
    }

    public Path framePath(String id, int iteration) {
        if (iteration < 0) {
            throw new IllegalArgumentException("Iteration must be non-negative.");
        }
        return getJob(id).framePath(iteration);
    }

    private void runJob(DlgJob job) {
        try {
            job.start();
            job.broadcast("status", job.snapshot());
            DlgJobParameters p = job.parameters();
            worker.run(new DlgWorkerRequest(
                    job.id(),
                    job.jobDir(),
                    job.targetImage(),
                    p.sampleId(),
                    p.sigma(),
                    p.iterations(),
                    p.frameEvery(),
                    p.seed()
            ), update -> handleUpdate(job, update));
            job.finish();
            job.broadcast("done", job.snapshot());
        } catch (Exception e) {
            job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            job.broadcast("error", job.snapshot());
        } finally {
            job.completeEmitters();
        }
    }

    private void handleUpdate(DlgJob job, DlgWorkerUpdate update) {
        if ("target".equals(update.type())) {
            job.broadcast("target", job.snapshot());
            return;
        }
        if ("message".equals(update.type())) {
            job.broadcast("message", Map.of("message", update.message() == null ? "" : update.message()));
            return;
        }

        job.apply(update);
        if ("progress".equals(update.type())) {
            job.broadcast("progress", job.snapshot());
        }
    }

    private DlgJob getJob(String id) {
        DlgJob job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("Unknown DLG job: " + id);
        }
        return job;
    }

    private static void validate(DlgCreateJobCommand command) {
        if (Double.isNaN(command.sigma()) || command.sigma() < 0 || command.sigma() > 5) {
            throw new IllegalArgumentException("Sigma must be between 0 and 5.");
        }
        if (command.iterations() < 1 || command.iterations() > 1000) {
            throw new IllegalArgumentException("Iterations must be between 1 and 1000.");
        }
        if (command.frameEvery() < 1 || command.frameEvery() > command.iterations()) {
            throw new IllegalArgumentException("Frame frequency must be between 1 and the iteration count.");
        }
        if (command.hasImage() && command.imageBytes().length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image uploads are limited to 5 MB.");
        }
        if (!command.hasImage() && !command.sampleId().matches("[A-Za-z0-9:_-]{1,40}")) {
            throw new IllegalArgumentException("Sample id contains unsupported characters.");
        }
    }

    private static void writeNormalizedImage(byte[] imageBytes, Path output) throws IOException {
        BufferedImage input = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (input == null) {
            throw new IllegalArgumentException("Upload must be a readable image file.");
        }

        BufferedImage normalized = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(input, 0, 0, 32, 32, null);
        } finally {
            graphics.dispose();
        }

        ImageIO.write(normalized, "png", output.toFile());
    }
}
