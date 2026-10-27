package app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves local frontend assets before the public feed catch-all route.
 *
 * <p>Assets are read once and served with an ETag; browsers revalidate on every page load
 * (asset URLs are not fingerprinted) and receive 304 when nothing changed.
 */
@RestController
public class FrontendAssetResource {
    /** Asset files served below; they are reachable without signing in. */
    public static final Set<String> ASSETS = Set.of("admin.css", "ai-settings.js", "reader-page.js", "admin-controls.css", "admin-views.css", "reader.css", "briefing.css", "htmx.min.js", "alpine.min.js", "ui.js",
            "reader.js", "admin.js", "unread.css", "search.css", "article.css", "article.js",
            "compact-summary.js", "radar.css", "radar.js", "radar-community.js",
            "ui-controls.css");

    private final Map<String, Asset> cache = new ConcurrentHashMap<>();

    @GetMapping({"/favicon.svg", "/favicon.ico"})
    public ResponseEntity<String> favicon() {
        return serve("favicon.svg", MediaType.valueOf("image/svg+xml"));
    }

    @GetMapping({"/{file:admin\\.css}", "/{file:ai-settings\\.js}", "/{file:reader-page\\.js}", "/{file:admin-controls\\.css}", "/{file:admin-views\\.css}", "/{file:reader\\.css}", "/{file:briefing\\.css}", "/{file:htmx\\.min\\.js}", "/{file:alpine\\.min\\.js}",
            "/{file:ui\\.js}", "/{file:reader\\.js}", "/{file:admin\\.js}", "/{file:unread\\.css}",
            "/{file:search\\.css}", "/{file:article\\.css}", "/{file:article\\.js}", "/{file:compact-summary\\.js}", "/{file:radar\\.css}", "/{file:radar\\.js}", "/{file:radar-community\\.js}", "/{file:ui-controls\\.css}"})
    public ResponseEntity<String> asset(@PathVariable String file) {
        MediaType mediaType = file.endsWith(".css")
                ? MediaType.valueOf("text/css")
                : MediaType.valueOf("text/javascript");
        return serve(file, mediaType);
    }

    private ResponseEntity<String> serve(String file, MediaType mediaType) {
        Asset asset = cache.computeIfAbsent(file, FrontendAssetResource::readAsset);
        // Spring answers 304 itself when If-None-Match matches. The ETag is weak because Tomcat
        // skips compression for responses with a strong ETag.
        return ResponseEntity.ok()
                .eTag(asset.etag())
                .cacheControl(CacheControl.noCache())
                .contentType(mediaType)
                .body(asset.content());
    }

    private record Asset(String content, String etag) {}

    private static Asset readAsset(String file) {
        String path = "static/" + file;
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("asset not found: " + file);
            }
            byte[] bytes = stream.readAllBytes();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            String etag = "W/\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
            return new Asset(new String(bytes, StandardCharsets.UTF_8), etag);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read asset: " + file, e);
        }
    }
}
