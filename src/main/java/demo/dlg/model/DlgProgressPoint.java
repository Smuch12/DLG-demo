package demo.dlg.model;

public record DlgProgressPoint(
        int iteration,
        Double loss,
        Double mse,
        Double psnr,
        String frameUrl
) {
}
