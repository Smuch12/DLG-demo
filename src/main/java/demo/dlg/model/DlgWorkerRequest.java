package demo.dlg.model;

import java.nio.file.Path;

public record DlgWorkerRequest(
        String jobId,
        Path jobDir,
        Path targetImage,
        String sampleId,
        double sigma,
        int iterations,
        int frameEvery,
        long seed
) {
}
