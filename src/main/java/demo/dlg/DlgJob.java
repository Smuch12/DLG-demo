package demo.dlg;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import demo.dlg.model.DlgJobParameters;
import demo.dlg.model.DlgJobSnapshot;
import demo.dlg.model.DlgProgressPoint;
import demo.dlg.model.DlgStatus;
import demo.dlg.model.DlgWorkerUpdate;

class DlgJob {
    private final String id;
    private final DlgJobParameters parameters;
    private final Path jobDir;
    private final Path targetImage;
    private final List<DlgProgressPoint> progress = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Instant createdAt = Instant.now();

    private volatile DlgStatus status = DlgStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile int currentIteration;
    private volatile Double loss;
    private volatile Double mse;
    private volatile Double psnr;
    private volatile String latestFrameUrl;
    private volatile String finalFrameUrl;
    private volatile String error;

    DlgJob(String id, DlgJobParameters parameters, Path jobDir, Path targetImage) {
        this.id = id;
        this.parameters = parameters;
        this.jobDir = jobDir;
        this.targetImage = targetImage;
    }

    String id() {
        return id;
    }

    Path jobDir() {
        return jobDir;
    }

    Path targetImage() {
        return targetImage;
    }

    DlgJobParameters parameters() {
        return parameters;
    }

    boolean isTerminal() {
        return status == DlgStatus.SUCCEEDED || status == DlgStatus.FAILED;
    }

    void start() {
        status = DlgStatus.RUNNING;
        startedAt = Instant.now();
    }

    void finish() {
        status = DlgStatus.SUCCEEDED;
        completedAt = Instant.now();
        finalFrameUrl = latestFrameUrl;
    }

    void fail(String message) {
        status = DlgStatus.FAILED;
        completedAt = Instant.now();
        error = message;
    }

    void apply(DlgWorkerUpdate update) {
        if (update.iteration() != null) {
            currentIteration = update.iteration();
        }
        if (update.loss() != null) {
            loss = update.loss();
        }
        if (update.mse() != null) {
            mse = update.mse();
        }
        if (update.psnr() != null) {
            psnr = update.psnr();
        }
        if (update.frame() != null && update.iteration() != null) {
            latestFrameUrl = frameUrl(update.iteration());
            progress.add(new DlgProgressPoint(update.iteration(), loss, mse, psnr, latestFrameUrl));
        }
    }

    DlgJobSnapshot snapshot() {
        return new DlgJobSnapshot(
                id,
                status,
                parameters,
                createdAt,
                startedAt,
                completedAt,
                currentIteration,
                parameters.iterations(),
                loss,
                mse,
                psnr,
                targetUrl(),
                latestFrameUrl,
                finalFrameUrl,
                error,
                progress
        );
    }

    String targetUrl() {
        return "/api/dlg/jobs/" + id + "/target.png";
    }

    String frameUrl(int iteration) {
        return "/api/dlg/jobs/" + id + "/frames/" + iteration + ".png";
    }

    Path framePath(int iteration) {
        return jobDir.resolve("frames").resolve(String.format("%06d.png", iteration));
    }

    void addEmitter(SseEmitter emitter) {
        emitters.add(emitter);
        Runnable cleanup = () -> emitters.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(err -> cleanup.run());
    }

    void broadcast(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    void completeEmitters() {
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }
}
