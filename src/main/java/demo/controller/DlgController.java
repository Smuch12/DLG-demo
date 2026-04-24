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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import demo.dlg.DlgJobService;
import demo.dlg.model.DlgCreateJobCommand;
import demo.dlg.model.DlgJobSnapshot;

@RestController
@RequestMapping("/api/dlg/jobs")
public class DlgController {
    private final DlgJobService jobService;

    public DlgController(DlgJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DlgJobSnapshot> createJob(
            @RequestParam(defaultValue = "cifar:25") String sampleId,
            @RequestParam(defaultValue = "0") double sigma,
            @RequestParam(defaultValue = "300") int iterations,
            @RequestParam(defaultValue = "10") int frameEvery,
            @RequestParam(defaultValue = "1234") long seed,
            @RequestParam(required = false) MultipartFile image
    ) throws IOException {
        byte[] imageBytes = image == null || image.isEmpty() ? null : image.getBytes();
        String contentType = image == null ? null : image.getContentType();
        DlgJobSnapshot snapshot = jobService.createJob(new DlgCreateJobCommand(
                sampleId,
                sigma,
                iterations,
                frameEvery,
                seed,
                imageBytes,
                contentType
        ));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(snapshot);
    }

    @GetMapping("/{id}")
    public DlgJobSnapshot getJob(@PathVariable String id) {
        return jobService.getSnapshot(id);
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        return jobService.subscribe(id);
    }

    @GetMapping("/{id}/target.png")
    public ResponseEntity<FileSystemResource> target(@PathVariable String id) {
        return png(jobService.targetPath(id));
    }

    @GetMapping("/{id}/frames/{iteration}.png")
    public ResponseEntity<FileSystemResource> frame(@PathVariable String id, @PathVariable int iteration) {
        return png(jobService.framePath(id, iteration));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
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
