package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.TestPages;
import app.radar.RadarTestEnvironment;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RadarReaderTest {
    @TempDir
    Path directory;

    @Test
    void pagesAndDownloadsAreReadOnlyAndStatusesAreDistinct() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            var engine = TestPages.engine();
            var resource =
                    new RadarResource(env.service, env.store, env.config, new ReaderTemplateRenderer(engine, null));
            var mvc = MockMvcBuilders.standaloneSetup(resource).build();
            String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            mvc.perform(get("/reader/radar").param("date", date))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString("尚未生成")));
            mvc.perform(get("/reader/radar").param("date", "bad")).andExpect(status().isBadRequest());
            mvc.perform(get("/reader/radar/runs/missing")).andExpect(status().isNotFound());
            assertEquals(0, env.requests.get());
            env.save("https://a.test/article?a=1&b=2");
            var submitted = mvc.perform(post("/reader/radar/runs")
                            .param("date", date)
                            .param("zoneId", "Asia/Shanghai")
                            .param("requestKey", "reader"))
                    .andExpect(status().isAccepted())
                    .andReturn();
            String id = env.mapper
                    .readTree(submitted.getResponse().getContentAsString())
                    .path("runId")
                    .asText();
            env.worker.processNext();
            int calls = env.requests.get();
            String html = mvc.perform(get("/reader/radar").param("date", date))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertTrue(html.contains("站内阅读"));
            assertTrue(html.contains("摘要"));
            mvc.perform(get("/reader/radar/runs/" + id))
                    .andExpect(jsonPath("state").value("success"));
            mvc.perform(get("/reader/radar/runs/" + id + "/markdown"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")));
            assertEquals(calls, env.requests.get());
            assertFalse(env.store.reading().isRead(app.auth.UserAccount.OWNER_ID, "https://a.test/article?a=1&b=2"));
        }
    }
}
