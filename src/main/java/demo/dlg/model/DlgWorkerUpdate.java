package demo.dlg.model;

public record DlgWorkerUpdate(
        String type,
        Integer iteration,
        Double loss,
        Double mse,
        Double psnr,
        String frame,
        String message
) {
}
