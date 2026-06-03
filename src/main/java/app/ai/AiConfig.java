package app.ai;

/**
 * Persisted Ollama configuration.
 *
 * @param host    Ollama base URL (e.g. {@code http://localhost:11434})
 * @param model   Ollama model name (e.g. {@code llama3.2})
 * @param enabled whether the scheduler should run AI enrichment after each fetch
 */
public record AiConfig(String host, String model, boolean enabled) {
    public static final String DEFAULT_HOST  = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "llama3.2";

    public AiConfig {
        if (host  == null || host.isBlank())  host  = DEFAULT_HOST;
        if (model == null || model.isBlank()) model = DEFAULT_MODEL;
    }

    /** Returns a config with default values (disabled). */
    public static AiConfig defaults() {
        return new AiConfig(DEFAULT_HOST, DEFAULT_MODEL, false);
    }
}
