package demo.dlg.model;

public record DlgJobParameters(
        String sampleId,
        double sigma,
        int iterations,
        int frameEvery,
        long seed,
        boolean customImage
) {
}
