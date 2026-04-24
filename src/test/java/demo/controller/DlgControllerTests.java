package demo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import demo.dlg.DlgJobService;
import demo.dlg.DlgWorker;
import demo.dlg.model.DlgWorkerRequest;
import demo.dlg.model.DlgWorkerUpdate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DlgControllerTests {
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @TempDir
    Path tempDir;

    MockMvc mockMvc;

    FakeDlgWorker worker;

    @BeforeEach
    void resetWorker() {
        worker = new FakeDlgWorker();
        DlgJobService service = new DlgJobService(worker, tempDir);
        mockMvc = MockMvcBuilders.standaloneSetup(new DlgController(service)).build();
    }

    @Test
    void createJobRejectsInvalidSigma() throws Exception {
        mockMvc.perform(multipart("/api/dlg/jobs")
                        .param("sampleId", "synthetic:badge")
                        .param("sigma", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Sigma")));
    }

    @Test
    void sseEndpointEmitsProgressEvents() throws Exception {
        worker.blockUntilReleased = true;
        String id = createJob();
        assertTrue(worker.started.await(5, TimeUnit.SECONDS));

        MvcResult stream = mockMvc.perform(get("/api/dlg/jobs/{id}/events", id))
                .andExpect(request().asyncStarted())
                .andReturn();

        worker.release.countDown();

        mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:progress")))
                .andExpect(content().string(containsString("\"currentIteration\":1")));
    }

    @Test
    void frameEndpointRejectsMissingFrames() throws Exception {
        String id = createJob();
        assertTrue(worker.finished.await(5, TimeUnit.SECONDS));

        mockMvc.perform(get("/api/dlg/jobs/{id}/frames/{iteration}.png", id, 999))
                .andExpect(status().isNotFound());
    }

    private String createJob() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/dlg/jobs")
                        .param("sampleId", "synthetic:badge")
                        .param("sigma", "0")
                        .param("iterations", "3")
                        .param("frameEvery", "1")
                        .param("seed", "1234"))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode node = jsonMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    static class FakeDlgWorker implements DlgWorker {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        volatile boolean blockUntilReleased;

        @Override
        public void run(DlgWorkerRequest request, Consumer<DlgWorkerUpdate> updates) throws Exception {
            started.countDown();
            writePng(request.jobDir().resolve("target.png"));
            updates.accept(new DlgWorkerUpdate("target", null, null, null, null, null, "target ready"));

            if (blockUntilReleased) {
                release.await(5, TimeUnit.SECONDS);
            }

            Path frame = request.jobDir().resolve("frames").resolve("000001.png");
            Files.createDirectories(frame.getParent());
            writePng(frame);
            updates.accept(new DlgWorkerUpdate("progress", 1, 0.25, 0.1, 10.0, "frames/000001.png", null));
            finished.countDown();
        }

        private static void writePng(Path path) throws IOException {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(image, "png", path.toFile());
        }
    }
}
