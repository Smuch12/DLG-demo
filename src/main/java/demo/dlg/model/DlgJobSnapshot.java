package demo.dlg.model;

import java.time.Instant;
import java.util.List;

public record DlgJobSnapshot(
        String id,
        DlgStatus status,
        DlgJobParameters parameters,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        int currentIteration,
        int totalIterations,
        Double loss,
        Double mse,
        Double psnr,
        String targetUrl,
        String latestFrameUrl,
        String finalFrameUrl,
        String error,
        List<DlgProgressPoint> progress
) {
}
