package demo.dlg;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import demo.dlg.model.DlgCreateJobCommand;

class DlgJobServiceTests {
    @TempDir
    Path tempDir;

    @Test
    void rejectsNegativeSigma() {
        DlgJobService service = new DlgJobService((request, updates) -> {
        }, tempDir);

        assertThrows(IllegalArgumentException.class, () -> service.createJob(new DlgCreateJobCommand(
                "synthetic:badge",
                -0.1,
                10,
                1,
                1234,
                null,
                null
        )));
    }

    @Test
    void rejectsUnreadableUpload() {
        DlgJobService service = new DlgJobService((request, updates) -> {
        }, tempDir);

        assertThrows(IllegalArgumentException.class, () -> service.createJob(new DlgCreateJobCommand(
                "synthetic:badge",
                0,
                10,
                1,
                1234,
                "not an image".getBytes(),
                "text/plain"
        )));
    }
}
