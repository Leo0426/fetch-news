package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.radar.RadarJson;
import app.radar.RadarTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiConfigStoreTest {
    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void malformedConfigurationFileReportsLocationWithoutItsContents() throws Exception {
        Path file = directory.resolve("invalid.json");
        Files.writeString(file, "{\n  \"apiKey\": \"PRIVATE\",\n  x\n}");
        var failure = assertThrows(IllegalStateException.class, () -> new AiConfigStore(file, mapper));
        assertTrue(failure.getMessage().contains("第 3 行"));
        assertFalse(failure.toString().contains("PRIVATE"));
    }

    @Test
    void migratesLegacySettingsAndPersistsMultipleProvidersWithOneActive() throws Exception {
        Path path = directory.resolve("ai.json");
        Files.writeString(path, "{\"host\":\"http://localhost:11434\",\"model\":\"local\",\"enabled\":true}");
        var store = new AiConfigStore(path, mapper);
        assertEquals("ollama", store.get().protocol());
        String id = store.saveProfile(
                null,
                "Relay",
                new AiConfig("https://relay.test/v1", "remote", true, "openai", "secret", 2048, false, "max_tokens"),
                true);
        assertEquals(2, store.settings().profiles().size());
        assertEquals("openai", new AiConfigStore(path, mapper).get().protocol());
        assertEquals("secret", new AiConfigStore(path, mapper).get().apiKey());
        assertFalse(store.get().toString().contains("secret"));
        assertFalse(mapper.writeValueAsString(store.get().withoutCredentials()).contains("secret"));
        store.activate("default");
        assertEquals("local", store.get().model());
        assertTrue(store.get().enabled());
        store.deleteProfile(id);
        assertEquals(1, store.settings().profiles().size());
        assertThrows(IllegalArgumentException.class, () -> store.deleteProfile("default"));
    }

    @Test
    void failedSaveDoesNotReplaceCurrentSettingsAndInvalidUrlsAreRejected() throws Exception {
        Path file = directory.resolve("ai.json");
        var store = new AiConfigStore(file, mapper);
        store.save(new AiConfig("http://localhost:11434", "saved", true));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(Exception.class, () -> store.save(new AiConfig(null, "new", true)));
        assertEquals("saved", store.get().model());
        for (String host :
                List.of("file:///tmp/a", "https://user:pass@relay.test/v1", "https://relay.test/v1?key=secret"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AiConfig(host, "m", true, "openai", "k", 2048, false, "max_tokens"));
    }

    @Test
    void rejectsHeaderUnsafeKeysWithoutIncludingTheKeyInErrors() {
        for (String key : List.of("SECRET-KEY-中", "SECRET-KEY-\u200b", "SECRET-KEY-\u0000")) {
            var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new AiConfig("https://relay.test/v1", "m", true, "openai", key, 1024, false, "max_tokens"));
            assertFalse(error.toString().contains("SECRET-KEY"));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "openai, https://relay.test/, https://relay.test/v1",
        "openai, https://relay.test/proxy/v1/, https://relay.test/proxy/v1",
        "openai, https://relay.test/proxy/v1/chat/completions/, https://relay.test/proxy/v1",
        "anthropic, https://relay.test/proxy/v1/messages, https://relay.test/proxy/v1",
        "openai, https://relay.test/custom-api, https://relay.test/custom-api",
        "ollama, http://localhost:11434/, http://localhost:11434",
        "openai, https://relay.test/proxy/v1/responses/, https://relay.test/proxy/v1",
        "gemini, https://relay.test/, https://relay.test/v1beta",
        "gemini, https://relay.test/p/v1/models/gemini-2:generateContent, https://relay.test/p/v1",
        "gemini, https://relay.test/p/v1beta/models, https://relay.test/p/v1beta"
    })
    void loadsExistingUrlsAsBaseUrlsAndPreservesProviderSettings(String protocol, String host, String expected)
            throws Exception {
        Path path = directory.resolve("ai.json");
        Files.writeString(
                path,
                mapper.writeValueAsString(Map.of(
                        "host",
                        host,
                        "model",
                        "custom-model",
                        "enabled",
                        true,
                        "protocol",
                        protocol,
                        "apiKey",
                        "saved-key")));
        var store = new AiConfigStore(path, mapper);
        assertEquals(expected, store.get().host());
        assertEquals(protocol, store.get().protocol());
        assertEquals("custom-model", store.get().model());
        assertEquals("saved-key", store.get().apiKey());
        assertEquals(List.of("custom-model"), store.profile("default").models());
        assertTrue(store.get().enabled());
        store.save(store.get());
        assertEquals(
                expected,
                mapper.readTree(path.toFile())
                        .path("profiles")
                        .get(0)
                        .path("config")
                        .path("host")
                        .asText());
        assertEquals(store.get(), new AiConfigStore(path, mapper).get());
    }

    @Test
    void existingCallersKeepSavedModelsWhenUpdatingProviderOptions() {
        var store = new AiConfigStore(directory.resolve("ai.json"), mapper);
        var config =
                new AiConfig("https://relay.test/v1", "first", true, "openai", "saved-key", 4096, false, "max_tokens");
        String id = store.saveProfile(null, "Relay", config, true, List.of("first", " second ", "second"));
        store.save(config.withEnabled(false));
        assertEquals(List.of("first", "second"), store.profile(id).models());
        assertFalse(store.get().enabled());
    }

    @Test
    void existingModelListsInheritLegacyControlsThenKeepIndependentOptionsAcrossSwitchAndReload() throws Exception {
        Path path = directory.resolve("ai.json");
        var legacy = Map.of("host", "https://relay.test", "model", "one", "protocol", "openai",
                "apiKey", "saved-key", "maxTokens", 512, "jsonMode", true, "tokenLimitParameter", "max_tokens");
        Files.writeString(path, mapper.writeValueAsString(Map.of("activeProfile", "relay", "enabled", true,
                "profiles", List.of(Map.of("id", "relay", "name", "Relay", "config", legacy,
                        "models", List.of("one", "two", "three"))))));
        var store = new AiConfigStore(path, mapper);
        var provider = store.profile("relay");
        var inherited = store.get().capabilities();
        assertEquals(Map.of("one", inherited, "two", inherited, "three", inherited), provider.modelCapabilities());
        var options = new ModelCapabilities(2048, false, "auto", "responses", Map.of("reasoning_effort", "low"));
        store.saveProfile("relay", "Relay", store.get(), true, provider.models(), Map.of("two", options));
        assertEquals(inherited, store.get().capabilities());
        store.saveProfile("relay", "Relay", store.get().withModel("two"), true, List.of("two", "one"));
        var reloaded = new AiConfigStore(path, mapper);
        assertEquals(options, reloaded.get().capabilities());
        assertEquals("saved-key", reloaded.get().apiKey());
        assertTrue(reloaded.get().enabled());
        assertEquals(Map.of("one", inherited, "two", options), reloaded.profile("relay").modelCapabilities());
        reloaded.saveProfile("relay", "Relay", reloaded.get().withModel("one"), true);
        assertEquals(inherited, reloaded.get().capabilities());
    }

    @Test
    void immutableParametersAndStyleAffectRadarIdentityWhileLegacySnapshotsRetainTheirCacheKeys() throws Exception {
        var original = new AiConfig(
                "https://relay.test", "custom", true, "openai", "PRIVATE", 512, false, "max_tokens");
        var expectedIdentity = List.of("openai", "https://relay.test/v1", "custom", 512, false, "max_tokens");
        assertEquals(RadarJson.hash(expectedIdentity), RadarJson.hash(original.identity()));
        var stops = new ArrayList<>(List.of("End"));
        var options = new ModelCapabilities(512, false, "max_tokens", "chat", Map.of("stop", stops, "stream", false));
        var configured = original.withCapabilities(options);
        stops.clear();
        var request = new RadarTypes.RunRequest("2026-01-01", "UTC", "take-1", configured, Map.of());
        String persisted = mapper.writeValueAsString(request);
        assertFalse(persisted.contains("PRIVATE"));
        var pinned = mapper.readValue(persisted, RadarTypes.RunRequest.class).ai();
        assertTrue(pinned.sameModel(configured));
        assertEquals(List.of("End"), pinned.parameters().get("stop"));
        assertFalse(pinned.sameModel(original));
        assertFalse(pinned.sameModel(configured.withCapabilities(
                new ModelCapabilities(512, false, "max_tokens", "responses", configured.parameters()))));
        assertEquals(configured.parameters(),
                configured.withHost("https://another.test").withEnabled(false).parameters());
    }

    @Test
    void acceptsGenerationControlsFromCompatibleProvidersWithoutOverridingRequestStructure() {
        var controls = Map.<String, Object>of(
                "enable_thinking", true, "chat_template_kwargs", Map.of("enable_thinking", false),
                "thinking", Map.of("type", "adaptive"),
                "reasoning", Map.of("effort", "experimental", "max_tokens", 1024, "enabled", true, "exclude", false),
                "adaptive_sampling_mode", "balanced", "thinkingBudgetRatio", 0.4);
        var config = new AiConfig(null, "custom", true, "openai", "key", 512, false, "auto", "auto", controls);
        assertEquals(controls, config.parameters());
    }

    @Test
    void rejectsConflictingAliasesWithoutExposingSubmittedValues() {
        for (Map<String, Object> options : List.<Map<String, Object>>of(
                Map.of("reasoning_effort", "high", "reasoning", Map.of("effort", "private")),
                Map.of("verbosity", "low", "text", Map.of("verbosity", "private")))) {
            var error = assertThrows(ModelCapabilities.InvalidOption.class,
                    () -> new ModelCapabilities(512, false, "auto", "auto", options));
            assertTrue(error.getMessage().contains("设置冲突"));
            assertFalse(error.getMessage().contains("private"));
        }
        var controls = Map.<String, Object>of("reasoning_effort", "high", "reasoning", Map.of("effort", "high"));
        assertEquals(controls, new ModelCapabilities(512, false, "auto", "chat", controls).parameters());
    }

    @Test
    void rejectsUnsafeOrInapplicableParametersBeforeSavingAnyChanges() {
        var store = new AiConfigStore(directory.resolve("ai.json"), mapper);
        var config = new AiConfig("https://relay.test", "one", true, "openai", "stored", 512, false, "auto");
        var settings = store.settings();
        for (Map<String, Object> params : List.<Map<String, Object>>of(
                Map.of("api_key", "private"), Map.of("messages", List.of("private")), Map.of("tools", List.of()),
                Map.of("background", true), Map.of("model", "other"), Map.of("host", "https://other.test"),
                Map.of("text", Map.of("api_key", "private")), Map.of("logit_bias", Map.of("private", 20)),
                Map.of("temperature", Double.NaN), Map.of("stream", "yes"),
                Map.of("sampling_proxy", "private"), Map.of("reasoning", Map.of("thinkingApiKey", "private")),
                Map.of("chat_template_kwargs", Map.of("tools", "private")), Map.of("output_config", Map.of()))) {
            var error = assertThrows(IllegalArgumentException.class, () -> store.saveProfile(
                    null, "Relay", config, true, List.of("one", "two"),
                    Map.of("two", new ModelCapabilities(128, false, "auto", "auto", params))));
            assertFalse(error.getMessage().contains("private"));
            assertEquals(settings, store.settings());
        }
    }
}
