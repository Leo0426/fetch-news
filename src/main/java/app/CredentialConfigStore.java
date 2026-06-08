package app;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists {@link CredentialConfig} to a JSON file.
 *
 * <p>Environment variables always override stored values:
 * <ul>
 *   <li>{@code BILIBILI_COOKIE} → global Bilibili cookie</li>
 *   <li>{@code BILIBILI_COOKIE_{uid}} → per-UID Bilibili cookie</li>
 *   <li>{@code TWITTER_COOKIE} → Twitter cookie</li>
 * </ul>
 */
public class CredentialConfigStore {
    private static final Logger log = LoggerFactory.getLogger(CredentialConfigStore.class);

    private final Path filePath;
    private final ObjectMapper mapper;
    private volatile CredentialConfig current;

    public CredentialConfigStore(Path filePath, ObjectMapper mapper) {
        this.filePath = filePath;
        this.mapper   = mapper;
        this.current  = load();
    }

    // ── read accessors (env var overrides stored value) ───────────────────────

    /** Global Bilibili cookie. Env {@code BILIBILI_COOKIE} takes precedence. */
    public String getBilibiliCookie() {
        String env = System.getenv("BILIBILI_COOKIE");
        if (env != null && !env.isBlank()) return env;
        return current.bilibiliCookie();
    }

    /**
     * Bilibili cookie for a specific UID.
     * Priority: {@code BILIBILI_COOKIE_{uid}} env var → stored per-UID cookie
     * → global Bilibili cookie.
     */
    public String getBilibiliCookieForUid(String uid) {
        String envSpecific = System.getenv("BILIBILI_COOKIE_" + uid);
        if (envSpecific != null && !envSpecific.isBlank()) return envSpecific;
        String stored = current.bilibiliUidCookies().get(uid);
        if (stored != null && !stored.isBlank()) return stored;
        return getBilibiliCookie();
    }

    /** Returns true if any Bilibili cookie is available for the given UID. */
    public boolean hasBilibiliCookieForUid(String uid) {
        String cookie = getBilibiliCookieForUid(uid);
        return cookie != null && !cookie.isBlank();
    }

    /** Twitter cookie. Env {@code TWITTER_COOKIE} takes precedence. */
    public String getTwitterCookie() {
        String env = System.getenv("TWITTER_COOKIE");
        if (env != null && !env.isBlank()) return env;
        return current.twitterCookie();
    }

    /** Returns the stored config (without env var overrides), for display in the UI. */
    public CredentialConfig getStored() { return current; }

    // ── write ─────────────────────────────────────────────────────────────────

    /** Saves a new config and returns it. */
    public CredentialConfig save(CredentialConfig config) {
        try {
            Files.createDirectories(filePath.getParent() != null
                    ? filePath.getParent() : Path.of("."));
            mapper.writeValue(filePath.toFile(), config);
            current = config;
        } catch (IOException e) {
            log.warn("failed to save credential config to {}: {}", filePath, e.getMessage());
        }
        return config;
    }

    /**
     * Updates a single per-UID Bilibili cookie entry and persists the result.
     * Pass a blank value to remove the entry.
     */
    public CredentialConfig putUidCookie(String uid, String cookie) {
        var map = new HashMap<>(current.bilibiliUidCookies());
        if (cookie == null || cookie.isBlank()) map.remove(uid);
        else map.put(uid, cookie);
        return save(new CredentialConfig(current.bilibiliCookie(), map,
                current.bilibiliUidNames(), current.twitterCookie()));
    }

    // ── persistence ───────────────────────────────────────────────────────────

    private CredentialConfig load() {
        if (!Files.exists(filePath)) return CredentialConfig.defaults();
        try {
            // Deserialize; Map<String,String> needs type handling
            var node = mapper.readTree(filePath.toFile());
            String bili = node.path("bilibiliCookie").asText("");
            String twit = node.path("twitterCookie").asText("");
            var uidMap   = new HashMap<String, String>();
            var nameMap  = new HashMap<String, String>();
            node.path("bilibiliUidCookies").fields()
                    .forEachRemaining(e -> uidMap.put(e.getKey(), e.getValue().asText("")));
            node.path("bilibiliUidNames").fields()
                    .forEachRemaining(e -> nameMap.put(e.getKey(), e.getValue().asText("")));
            return new CredentialConfig(bili, uidMap, nameMap, twit);
        } catch (IOException e) {
            log.warn("failed to load credential config from {}: {}", filePath, e.getMessage());
            return CredentialConfig.defaults();
        }
    }
}
