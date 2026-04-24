package demo.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import demo.dlg.DlgSamplePreviewService;

@RestController
@RequestMapping("/api/dlg/samples")
public class DlgSampleController {
    private final DlgSamplePreviewService previewService;

    public DlgSampleController(DlgSamplePreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/preview")
    public ResponseEntity<FileSystemResource> preview(@RequestParam(defaultValue = "cifar:25") String sampleId)
            throws IOException, InterruptedException {
        return png(previewService.previewPath(sampleId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> previewError(IOException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(exception.getMessage());
    }

    private static ResponseEntity<FileSystemResource> png(Path path) {
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new FileSystemResource(path));
    }
}
