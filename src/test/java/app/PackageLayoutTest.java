package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the layering readable: domain packages never reach up into the wiring package ({@code app}),
 * the fetch pipeline or the web packages ({@code app.admin}, {@code app.reader}).
 */
class PackageLayoutTest {
    private static final List<String> DOMAIN =
            List.of("core", "routes", "store", "support", "ai", "radar", "feishu");
    private static final Pattern IMPORT = Pattern.compile("^import (app(?:\\.[a-z]+)*)\\.[A-Z][\\w.]*;$", Pattern.MULTILINE);
    private static final List<String> UPPER = List.of("app", "app.fetch", "app.admin", "app.reader");

    @Test
    void domainPackagesDoNotDependOnWiringFetchOrWebPackages() throws IOException {
        Path sources = SourceSizeTest.projectRoot().resolve("src/main/java/app");
        List<String> violations = new ArrayList<>();
        for (String domain : DOMAIN) {
            try (Stream<Path> files = Files.walk(sources.resolve(domain))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    Matcher imported = IMPORT.matcher(Files.readString(file));
                    while (imported.find()) {
                        if (UPPER.contains(imported.group(1))) {
                            violations.add(sources.relativize(file) + " imports " + imported.group(0));
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "move the shared type down into app.core or the domain package");
    }
}
