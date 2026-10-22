package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.OllamaClient;
import app.core.CacheService;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class AdminAiSettingsTest {
    @TempDir Path directory;
    private AiConfigStore config;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        var mapper=new ObjectMapper();config=new AiConfigStore(directory.resolve("ai.json"),mapper);
        var runtime=new AppRuntime(new CacheService(),new RouteRegistry(List.of()),new RouteConfigStore(directory.resolve("routes.json"),mapper));
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var renderer=new AdminTemplateRenderer(engine,null,runtime,new AppInfo());
        var admin=new AdminResource(runtime,mapper,renderer,null,config,new OllamaClient(config,mapper),null,null,null,null);
        mvc=MockMvcBuilders.standaloneSetup(admin).build();
    }

    @Test void savesCustomProviderWithoutSwitchingUntilExplicitlyActivated() throws Exception {
        mvc.perform(post("/index/ai").param("name", "My custom provider").param("protocol", "openai")
                .param("host", "https://custom.example/proxy/v1").param("model", "my-own-model")
                .param("apiKey", "custom-key").contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
        assertEquals("default", config.settings().activeProfile());
        assertFalse(config.get().enabled());
        var provider = config.settings().profiles().stream().filter(p -> !p.id().equals("default")).findFirst().orElseThrow();
        var reloaded = new AiConfigStore(directory.resolve("ai.json"), new ObjectMapper());
        assertEquals(provider, reloaded.profile(provider.id()));
        String html = mvc.perform(get("/index/ai").param("profileId", provider.id()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("my-own-model", Jsoup.parse(html).select("input[name=model]").val());
        mvc.perform(post("/index/ai/activate").param("profileId", provider.id())).andExpect(status().isOk());
        assertEquals("https://custom.example/proxy/v1", config.get().host());
        assertEquals("my-own-model", config.get().model());
        assertEquals("custom-key", config.get().apiKey());
        assertFalse(config.get().enabled());
    }

    @Test void managesMultiplePlatformsWithoutEverReflectingSavedKeys() throws Exception {
        String html=mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name","Relay").param("protocol","openai").param("host","https://relay.test/v1").param("model","custom")
                .param("apiKey","SECRET-KEY").param("enabled","on").param("maxTokens","2048").param("activate","true"))
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

    @Test void savesAndDisplaysBaseUrlWhenPastingACompleteEndpoint() throws Exception {
        String html = mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name", "Relay").param("protocol", "openai")
                .param("host", "https://relay.test/proxy/v1/chat/completions/").param("model", "custom")
                .param("apiKey", "saved-key").param("activate", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("https://relay.test/proxy/v1", Jsoup.parse(html).select("input[name=host]").val());
        var reloaded = new AiConfigStore(directory.resolve("ai.json"), new ObjectMapper());
        assertEquals("https://relay.test/proxy/v1", reloaded.get().host());
        assertFalse(Files.readString(directory.resolve("ai.json")).contains("/chat/completions"));
        mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("profileId", config.settings().activeProfile()).param("name", "Relay")
                .param("protocol", "openai").param("host", "https://relay.test/proxy/v1").param("model", "custom"))
                .andExpect(status().isOk());
        assertEquals("saved-key", config.get().apiKey());
    }

    @Test void savesProviderModelListAndRemovesModelsWithoutChangingOtherProviders() throws Exception {
        mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name", "Relay").param("protocol", "openai").param("host", "https://relay.test/v1")
                .param("model", "second-model").param("modelIds", "first-model", "second-model")
                .param("apiKey", "saved-key").param("activate", "true"))
                .andExpect(status().isOk());
        var mapper = new ObjectMapper();
        var stored = mapper.readTree(directory.resolve("ai.json").toFile()).path("profiles").get(1);
        assertEquals(List.of("first-model", "second-model"), mapper.convertValue(stored.path("models"), List.class));
        String id = config.settings().activeProfile();
        mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("profileId", id).param("name", "Relay").param("protocol", "openai")
                .param("host", "https://relay.test/v1").param("model", "second-model").param("modelIds", "second-model"))
                .andExpect(status().isOk());
        var reloaded = new AiConfigStore(directory.resolve("ai.json"), mapper);
        stored = mapper.readTree(directory.resolve("ai.json").toFile()).path("profiles").get(1);
        assertEquals(List.of("second-model"), mapper.convertValue(stored.path("models"), List.class));
        assertEquals("second-model", reloaded.get().model());
        assertEquals("saved-key", reloaded.get().apiKey());
        assertEquals("llama3.2", reloaded.profile("default").config().model());
    }

    @Test void rejectsEmptyModelListsAndRemovingTheDefaultModelWithoutChangingSavedSettings() throws Exception {
        String id = config.saveProfile(null, "Relay", new AiConfig("https://relay.test/v1", "first", false,
                "openai", "saved-key", 4096, false, "max_tokens"), true, List.of("first", "second"));
        var saved = config.settings();
        for (String[] models : List.of(new String[]{""}, new String[]{"second"})) {
            mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("profileId", id).param("name", "Relay").param("protocol", "openai")
                    .param("host", "https://relay.test/v1").param("model", "first").param("modelIds", models))
                    .andExpect(status().isBadRequest());
            assertEquals(saved, config.settings());
            assertEquals(saved, new AiConfigStore(directory.resolve("ai.json"), new ObjectMapper()).settings());
        }
        mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("profileId", id).param("name", "Relay").param("protocol", "openai")
                .param("host", "https://relay.test/v1").param("model", "second").param("modelIds", "second"))
                .andExpect(status().isOk());
        assertEquals(List.of("second"), config.profile(id).models());
        assertEquals("second", config.get().model());
    }

    @Test void testsUnsavedProviderFormWithoutSavingOrSwitching() throws Exception {
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"draft-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String html = mvc.perform(post("/index/ai/test").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("host", "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1")
                    .param("protocol", "openai").param("model", "draft-model").param("apiKey", "draft-key"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(Jsoup.parse(html).text().contains("模型 draft-model 已列出"));
            assertEquals("Bearer draft-key", authorization.get());
            assertFalse(html.contains("draft-key"));
            assertEquals("default", config.settings().activeProfile());
            assertEquals(1, config.settings().profiles().size());
            assertFalse(Files.exists(directory.resolve("ai.json")));
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1";
            String id = config.saveProfile(null, "Saved provider",
                    new AiConfig(endpoint, "saved-model", false, "openai", "saved-key", 4096, false, "max_tokens"), false);
            mvc.perform(post("/index/ai/test").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("profileId", id).param("host", endpoint).param("protocol", "openai").param("model", "draft-model"))
                    .andExpect(status().isOk());
            assertEquals("Bearer saved-key", authorization.get());
            mvc.perform(post("/index/ai/test").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("profileId", id).param("host", "https://other.example/v1").param("protocol", "openai").param("model", "draft-model"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/index/ai/test").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("profileId", id).param("host", endpoint).param("protocol", "openai").param("model", "draft-model")
                    .param("clearKey", "true")).andExpect(status().isOk());
            assertNull(authorization.get());
            assertEquals("saved-key", config.profile(id).config().apiKey());
            assertEquals("saved-model", config.profile(id).config().model());
            assertEquals("default", config.settings().activeProfile());
        } finally { server.stop(0); }
    }

    @Test void globalSwitchIsIndependentOfProviderEditsAndActivation() throws Exception {
        mvc.perform(post("/index/ai/enabled").param("enabled", "true")).andExpect(status().isOk());
        mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name", "Another provider").param("protocol", "anthropic").param("activate", "true")
                .param("host", "https://custom.example/v1").param("model", "custom-model"))
                .andExpect(status().isOk());
        assertTrue(config.get().enabled());
        assertTrue(new AiConfigStore(directory.resolve("ai.json"), new ObjectMapper()).get().enabled());
        String active = config.settings().activeProfile();
        mvc.perform(post("/index/ai/enabled")).andExpect(status().isOk());
        assertFalse(config.get().enabled());
        assertEquals(active, config.settings().activeProfile());
        assertEquals("custom-model", config.get().model());
    }

    @Test void discoversModelsBeforeEnteringNameOrModelWithoutSavingProvider() throws Exception {
        var authorization = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            byte[] body = "{\"data\":[{\"id\":\"custom-first\"},{\"id\":\"custom-second\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String result = mvc.perform(post("/index/ai/models").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("host", "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1/chat/completions")
                    .param("protocol", "openai").param("apiKey", "discovery-key"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.models[0]").value("custom-first"))
                    .andExpect(jsonPath("$.models[1]").value("custom-second"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertEquals("GET", method.get());
            assertEquals("Bearer discovery-key", authorization.get());
            assertFalse(result.contains("discovery-key"));
            assertEquals("default", config.settings().activeProfile());
            assertEquals(1, config.settings().profiles().size());
            assertFalse(config.get().enabled());
            assertFalse(Files.exists(directory.resolve("ai.json")));
        } finally { server.stop(0); }
    }

    @Test void discoversAuthenticatedOllamaModelsUsingSavedProviderKey() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            boolean authenticated = "Bearer ollama-key".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            String content = exchange.getRequestURI().getPath().equals("/api/version")
                    ? "{\"version\":\"test\"}" : "{\"models\":[{\"name\":\"local-model:latest\"}]}";
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(authenticated ? 200 : 401, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            String id = config.saveProfile(null, "Remote Ollama", new AiConfig(endpoint, "saved-model", false,
                    "ollama", "ollama-key", 4096, true, "max_tokens"), false);
            mvc.perform(post("/index/ai/models").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("profileId", id).param("host", endpoint).param("protocol", "ollama"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.models[0]").value("local-model:latest"));
            assertEquals("default", config.settings().activeProfile());
            assertEquals("saved-model", config.profile(id).config().model());
            mvc.perform(post("/index/ai/models").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("profileId", id).param("host", "https://another.example").param("protocol", "ollama"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.models").isEmpty());
        } finally { server.stop(0); }
    }

    @Test void failedDiscoveryStillAllowsSavingAManuallyEnteredModel() throws Exception {
        var upstreamStatus = new AtomicInteger(404);
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] body = "Invalid upstream response with private details".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(upstreamStatus.get(), body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            for (int code : List.of(404, 405, 401, 200)) {
                upstreamStatus.set(code);
                String body = mvc.perform(post("/index/ai/models").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("host", endpoint).param("protocol", "anthropic").param("apiKey", "anthropic-key"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.models").isEmpty())
                        .andExpect(jsonPath("$.message").isNotEmpty()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertFalse(body.contains("private details"));
            }
            assertEquals("anthropic-key", authorization.get());
            assertFalse(Files.exists(directory.resolve("ai.json")));
            mvc.perform(post("/index/ai").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("name", "Manual provider").param("host", endpoint).param("protocol", "anthropic")
                    .param("apiKey", "anthropic-key").param("model", "my-manual-model"))
                    .andExpect(status().isOk());
            assertEquals("my-manual-model", config.settings().profiles().getLast().config().model());
            assertEquals("default", config.settings().activeProfile());
        } finally { server.stop(0); }
    }
}
