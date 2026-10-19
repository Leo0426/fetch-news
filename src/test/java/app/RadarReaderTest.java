package app;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import app.radar.RadarTestEnvironment;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class RadarReaderTest {
    @TempDir Path directory;
    @Test void pagesAndDownloadsAreReadOnlyAndStatusesAreDistinct() throws Exception {
        try(var env=new RadarTestEnvironment(directory,"{\"enabled\":true}")) {
            var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
            var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
            var resource=new RadarResource(env.service,env.store,env.config,new RadarTemplateRenderer(engine));
            var mvc=MockMvcBuilders.standaloneSetup(resource).build();
            String date=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            mvc.perform(get("/reader/radar").param("date",date)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("尚未生成")));
            mvc.perform(get("/reader/radar").param("date","bad")).andExpect(status().isBadRequest());
            mvc.perform(get("/reader/radar/runs/missing")).andExpect(status().isNotFound());
            assertEquals(0,env.requests.get());
            env.save("https://a.test/article?a=1&b=2");
            var submitted=mvc.perform(post("/reader/radar/runs").param("date",date).param("zoneId","Asia/Shanghai").param("requestKey","reader"))
                    .andExpect(status().isAccepted()).andReturn();
            String id=env.mapper.readTree(submitted.getResponse().getContentAsString()).path("runId").asText();
            env.worker.processNext();
            int calls=env.requests.get();
            String html=mvc.perform(get("/reader/radar").param("date",date)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(html.contains("站内阅读")); assertTrue(html.contains("摘要"));
            mvc.perform(get("/reader/radar/runs/"+id)).andExpect(jsonPath("state").value("success"));
            mvc.perform(get("/reader/radar/runs/"+id+"/markdown")).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")));
            assertEquals(calls,env.requests.get());assertFalse(env.store.isRead("https://a.test/article?a=1&b=2"));
        }
    }
}
