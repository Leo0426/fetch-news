package app;

import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.feishu.FeishuConfigStore;
import app.feishu.FeishuPusher;
import app.store.FeedStore;
import app.radar.ProfileStore;
import app.radar.RadarConfigStore;
import app.radar.RadarService;
import app.radar.RadarScheduler;
import app.radar.RadarWorker;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.SQLException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Explicit Spring beans shared by the application.
 */
@Configuration
@EnableScheduling
public class AppConfig {
    /**
     * Provides the JSON mapper used by renderers and persisted route configuration.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Opens the SQLite feed store at the path given by the {@code fetch-news.db}
     * system property, defaulting to {@code feed-store.db} in the working directory.
     */
    @Bean(destroyMethod = "close")
    public FeedStore feedStore(ObjectMapper objectMapper) throws SQLException {
        Path dbPath = Path.of(System.getProperty("fetch-news.db", "feed-store.db"));
        return new FeedStore(dbPath, objectMapper);
    }

    /**
     * AI config store — persists Ollama host/model to {@code ai-config.json}.
     */
    @Bean
    public AiConfigStore aiConfigStore(ObjectMapper objectMapper) {
        java.nio.file.Path path = java.nio.file.Path.of(
                System.getProperty("fetch-news.ai-config", "ai-config.json"));
        return new AiConfigStore(path, objectMapper);
    }

    /**
     * Ollama client — reads host and model from {@link AiConfigStore} on every call
     * so admin-page changes take effect without a restart.
     */
    @Bean
    public OllamaClient ollamaClient(AiConfigStore aiConfigStore, ObjectMapper objectMapper) {
        return new OllamaClient(aiConfigStore, objectMapper);
    }

    /**
     * Article extractor that enriches feed items using Ollama and caches results in SQLite.
     */
    @Bean
    public ArticleExtractor articleExtractor(OllamaClient ollamaClient, FeedStore feedStore, AppRuntime runtime) {
        return new ArticleExtractor(ollamaClient, feedStore, runtime.fetchClient());
    }

    /**
     * Article summarizer — generates 2-3 sentence Chinese summaries for reader cards.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public ArticleSummarizer articleSummarizer(OllamaClient ollamaClient, FeedStore feedStore,
                                             ArticleExtractor extractor, AiConfigStore config) throws SQLException {
        return new ArticleSummarizer(ollamaClient, feedStore, extractor, config);
    }

    /**
     * Feishu config store — persists webhook URL and settings to {@code feishu-config.json}.
     */
    @Bean
    public FeishuConfigStore feishuConfigStore(ObjectMapper objectMapper) {
        Path path = Path.of(System.getProperty("fetch-news.feishu-config", "feishu-config.json"));
        return new FeishuConfigStore(path, objectMapper);
    }

    /**
     * Feishu pusher — sends new feed items to the configured webhook after each scheduled fetch.
     */
    @Bean
    public FeishuPusher feishuPusher(FeishuConfigStore feishuConfigStore, ObjectMapper objectMapper) {
        return new FeishuPusher(feishuConfigStore, objectMapper);
    }
    @Bean
    public RadarConfigStore radarConfigStore(ObjectMapper mapper) throws java.io.IOException {
        return new RadarConfigStore(Path.of(System.getProperty("fetch-news.radar-config", "radar-config.json")), mapper);
    }

    @Bean
    public ProfileStore profileStore(ObjectMapper mapper) throws java.io.IOException {
        return new ProfileStore(Path.of(System.getProperty("fetch-news.radar-profiles", "radar-profiles")), mapper);
    }

    @Bean
    public RadarService radarService(FeedStore store, RadarConfigStore config, ProfileStore profiles,
                                     AiConfigStore ai, AppRuntime runtime) {
        return new RadarService(store, config, profiles, ai, () -> {
            java.util.Map<String,String> categories = new java.util.HashMap<>();
            var registered = runtime.routeRegistry().categories();
            for (var route : runtime.routeConfigStore().list(runtime.routeRegistry().paths())) {
                String source = route.sourcePath() == null ? route.path() : route.sourcePath();
                for (var entry : registered.entrySet()) {
                    String pattern = entry.getKey().replaceAll(":[^/]+", "[^/]+");
                    if (source.matches(pattern)) { categories.put(route.path(), entry.getValue()); break; }
                }
            }
            return categories;
        });
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public RadarWorker radarWorker(FeedStore store, RadarConfigStore config, AiConfigStore ai,
                                   OllamaClient client, ArticleExtractor extractor, ObjectMapper mapper) throws SQLException {
        return new RadarWorker(store, config, ai, client, extractor, mapper, java.time.Clock.systemUTC());
    }

    @Bean
    public RadarScheduler radarScheduler(RadarConfigStore config, RadarService service) {
        return new RadarScheduler(config, service, java.time.Clock.systemUTC());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public app.radar.eval.EvaluationService evaluationService(FeedStore store, AiConfigStore ai, OllamaClient client, ObjectMapper mapper) throws Exception {
        return new app.radar.eval.EvaluationService(store, ai, client, mapper);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public app.feishu.RadarDeliveryService radarDeliveryService(FeedStore store, FeishuConfigStore config, ObjectMapper mapper) throws Exception {
        return new app.feishu.RadarDeliveryService(store, config, mapper);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public app.radar.CommunityContextService communityContextService(FeedStore store, AppRuntime runtime, ObjectMapper mapper) throws Exception {
        return new app.radar.CommunityContextService(store, runtime.fetchClient(), mapper);
    }

}
