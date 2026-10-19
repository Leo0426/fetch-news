package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.radar.ProfileStore;
import app.radar.RadarConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class RadarAdminTest {
    @TempDir Path directory;
    @Test void rendersAndSavesValidatedProfilesAndSourceQuotas() throws Exception {
        var mapper = new ObjectMapper();
        var profiles = new ProfileStore(directory.resolve("profiles"), mapper);
        var config = new RadarConfigStore(directory.resolve("radar.json"), mapper);
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var mvc = MockMvcBuilders.standaloneSetup(new RadarAdminResource(profiles, config, new RadarAdminRenderer(engine))).build();
        String html = mvc.perform(get("/index/radar")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("threshold.tech-news"));
        mvc.perform(post("/index/radar/profiles").param("id","custom").param("version","1")
                .param("match","my interest").param("scoring","my rubric").param("block.summary","my summary"))
                .andExpect(status().isOk());
        assertEquals("my rubric", profiles.loadAll().get("custom").scoring());
        mvc.perform(post("/index/radar/config").param("enabled","on").param("zoneId","Asia/Shanghai")
                .param("defaultProfile","custom").param("newSource","/hn").param("newProfiles","custom")
                .param("newGroup","AI").param("newCategories","AI,Tech").param("newLimit","3"))
                .andExpect(status().isOk());
        assertEquals(3,config.get().categoryGroups().get("AI").limit());
        assertEquals("custom",config.get().sources().get("/hn").profiles().getFirst());
        mvc.perform(post("/index/radar/config").param("defaultProfile","missing")).andExpect(status().isBadRequest());
        assertEquals("custom",config.get().defaultProfile());
    }
}
