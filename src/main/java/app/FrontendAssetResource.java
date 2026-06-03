package app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves local frontend assets before the public feed catch-all route.
 */
@RestController
public class FrontendAssetResource {
    /**
     * Serves CSS and JavaScript assets used by the admin page.
     *
     * @param file asset file name
     * @return asset content
     */
    @GetMapping({"/favicon.svg", "/favicon.ico"})
    public ResponseEntity<String> favicon(@PathVariable(required = false) String file) {
        String content = readAsset("favicon.svg");
        return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml")).body(content);
    }

    @GetMapping({"/{file:admin\\.css}", "/{file:htmx\\.min\\.js}", "/{file:alpine\\.min\\.js}"})
    public ResponseEntity<String> asset(@PathVariable String file) {
        String content = readAsset(file);
        MediaType mediaType = file.endsWith(".css")
                ? MediaType.valueOf("text/css")
                : MediaType.valueOf("text/javascript");
        return ResponseEntity.ok().contentType(mediaType).body(content);
    }

    private String readAsset(String file) {
        String path = "static/" + file;
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("asset not found: " + file);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read asset: " + file, e);
        }
    }
}
