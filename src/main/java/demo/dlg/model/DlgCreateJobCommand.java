package demo.dlg.model;

public record DlgCreateJobCommand(
        String sampleId,
        double sigma,
        int iterations,
        int frameEvery,
        long seed,
        byte[] imageBytes,
        String imageContentType
) {
    public boolean hasImage() {
        return imageBytes != null && imageBytes.length > 0;
    }
}
