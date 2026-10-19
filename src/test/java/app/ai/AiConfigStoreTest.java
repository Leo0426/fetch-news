package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiConfigStoreTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void migratesLegacySettingsAndPersistsMultipleProvidersWithOneActive() throws Exception {
        Path path=directory.resolve("ai.json");
        Files.writeString(path,"{\"host\":\"http://localhost:11434\",\"model\":\"local\",\"enabled\":true}");
        var store=new AiConfigStore(path,mapper);
        assertEquals("ollama",store.get().protocol());
        String id=store.saveProfile(null,"Relay",new AiConfig("https://relay.test/v1","remote",true,"openai","secret",2048,false,"max_tokens"),true);
        assertEquals(2,store.settings().profiles().size());
        assertEquals("openai",new AiConfigStore(path,mapper).get().protocol());
        assertEquals("secret",new AiConfigStore(path,mapper).get().apiKey());
        assertFalse(store.get().toString().contains("secret"));
        assertFalse(mapper.writeValueAsString(store.get().withoutCredentials()).contains("secret"));
        store.activate("default");assertEquals("local",store.get().model());assertTrue(store.get().enabled());
        store.deleteProfile(id);assertEquals(1,store.settings().profiles().size());
        assertThrows(IllegalArgumentException.class,()->store.deleteProfile("default"));
    }
    @Test void failedSaveDoesNotReplaceCurrentSettingsAndInvalidUrlsAreRejected() throws Exception {
        Path file=directory.resolve("ai.json");var store=new AiConfigStore(file,mapper);
        store.save(new AiConfig("http://localhost:11434","saved",true));
        Files.delete(file);Files.createDirectory(file);
        assertThrows(Exception.class,()->store.save(new AiConfig(null,"new",true)));
        assertEquals("saved",store.get().model());
        for(String host:java.util.List.of("file:///tmp/a","https://user:pass@relay.test/v1","https://relay.test/v1?key=secret"))
            assertThrows(IllegalArgumentException.class,()->new AiConfig(host,"m",true,"openai","k",2048,false,"max_tokens"));
    }
    @Test void rejectsHeaderUnsafeKeysWithoutIncludingTheKeyInErrors() {
        for(String key:java.util.List.of("SECRET-KEY-中","SECRET-KEY-\u200b","SECRET-KEY-\u0000")) {
            var error=assertThrows(IllegalArgumentException.class,()->new AiConfig("https://relay.test/v1","m",true,"openai",key,1024,false,"max_tokens"));
            assertFalse(error.toString().contains("SECRET-KEY"));
        }
    }

}
