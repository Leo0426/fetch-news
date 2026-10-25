package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
        "ollama, http://localhost:11434/, http://localhost:11434"
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
}
