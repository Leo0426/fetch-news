package app.radar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class RadarConfigStore {
    private final Path path;
    private final ObjectMapper mapper;
    private volatile RadarConfig current;

    public RadarConfigStore(Path path, ObjectMapper mapper) throws IOException {
        this.path = path.toAbsolutePath();
        this.mapper = mapper;
        current = Files.exists(path) ? mapper.readValue(path.toFile(), RadarConfig.class) : RadarConfig.defaults();
    }

    public RadarConfig get() { return current; }

    public synchronized void save(RadarConfig config) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".radar-", ".json");
        try {
            mapper.writeValue(temporary.toFile(), config);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            current = config;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
