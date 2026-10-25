package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Keeps every hand-written source file under the project's size limit. */
class SourceSizeTest {
    private static final int MAX_LINES = 500;

    @Test
    void noSourceFileExceedsTheLineLimit() throws IOException {
        Path root = projectRoot();
        List<String> oversized;
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            oversized = files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().matches(".*\\.(java|html|js|css)$"))
                    .filter(file -> !file.getFileName().toString().endsWith(".min.js"))
                    .filter(file -> lineCount(file) > MAX_LINES)
                    .map(file -> root.relativize(file) + " (" + lineCount(file) + " lines)")
                    .sorted()
                    .toList();
        }
        assertEquals(List.of(), oversized, "split files over " + MAX_LINES + " lines by responsibility");
    }

    /** admin.css was split in cascade order; pages must load every part exactly once, in that order. */
    @Test
    void pagesLoadTheSplitBaseStylesheetsOnceInCascadeOrder() throws IOException {
        List<String> parts = List.of("admin", "admin-controls", "admin-views", "reader", "briefing");
        Pattern link = Pattern.compile(
                "href=\"/(admin|admin-controls|admin-views|reader|briefing)\\.css\"");
        try (Stream<Path> files = Files.walk(projectRoot().resolve("src/main/resources/templates"))) {
            for (Path template : files.filter(file -> file.toString().endsWith(".html")).toList()) {
                List<String> found = link.matcher(Files.readString(template)).results().map(m -> m.group(1)).toList();
                if (!found.isEmpty()) assertEquals(parts, found, template.toString());
            }
        }
    }

    /** Tests run in build/test-runtime, so walk up to the checkout. */
    static Path projectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("settings.gradle not found above the working directory");
        return dir;
    }

    private static long lineCount(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
