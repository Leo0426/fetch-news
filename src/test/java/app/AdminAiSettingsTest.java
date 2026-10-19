package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.ai.AiConfigStore;
import app.ai.OllamaClient;
import app.core.CacheService;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class AdminAiSettingsTest {
    @TempDir Path directory;
    @Test void managesMultiplePlatformsWithoutEverReflectingSavedKeys() throws Exception {
        var mapper=new ObjectMapper();var config=new AiConfigStore(directory.resolve("ai.json"),mapper);
        var runtime=new AppRuntime(new CacheService(),new RouteRegistry(List.of()),new RouteConfigStore(directory.resolve("routes.json"),mapper));
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var renderer=new AdminTemplateRenderer(engine,null,runtime,new AppInfo());
        var admin=new AdminResource(runtime,mapper,renderer,null,config,new OllamaClient(config,mapper),null,null,null,null);
        var mvc=MockMvcBuilders.standaloneSetup(admin).build();
        String html=mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name","Relay").param("protocol","openai").param("host","https://relay.test/v1").param("model","custom")
                .param("apiKey","SECRET-KEY").param("enabled","on").param("maxTokens","2048"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(2,config.settings().profiles().size());assertEquals("openai",config.get().protocol());
        String id=config.settings().activeProfile();
        String rejected=mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name","Bad key").param("protocol","openai").param("host","https://relay.test/v1").param("model","m")
                .param("apiKey","SECRET-KEY-中"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(rejected.contains("SECRET-KEY"));assertEquals("SECRET-KEY",config.get().apiKey());
        assertFalse(html.contains("SECRET-KEY"));assertTrue(Jsoup.parse(html).select("input[name=apiKey]").attr("value").isEmpty());
        mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("profileId",id).param("name","Renamed")
                .param("protocol","openai").param("host","https://relay.test/v1").param("model","second").param("enabled","on"))
                .andExpect(status().isOk());assertEquals("SECRET-KEY",config.get().apiKey());
        mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("profileId",id).param("name","Renamed")
                .param("protocol","openai").param("host","https://another.test/v1").param("model","second").param("enabled","on"))
                .andExpect(status().isBadRequest());assertEquals("https://relay.test/v1",config.get().host());
        mvc.perform(post("/index/ai/activate").param("profileId","default")).andExpect(status().isOk());assertEquals("ollama",config.get().protocol());
        mvc.perform(get("/index/ai").param("profileId",id)).andExpect(status().isOk());
        mvc.perform(post("/index/ai/delete").param("profileId",id)).andExpect(status().isOk());assertEquals(1,config.settings().profiles().size());
    }
}
