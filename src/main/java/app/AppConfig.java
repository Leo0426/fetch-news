package app;

import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.ai.ArticleTriage;
import app.ai.OllamaClient;
import app.auth.AuthFilter;
import app.auth.AuthService;
import app.core.RouteConfig;
import app.core.Topic;
import app.feishu.FeishuConfigStore;
import app.feishu.FeishuPusher;
import app.radar.BuiltInProfiles;
import app.radar.RadarConfig;
import app.radar.RadarScheduler;
import app.radar.RadarService;
import app.radar.RadarWorker;
import app.store.FeedStore;
import app.support.SourceLabels;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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
        FeedStore store = new FeedStore(dbPath, objectMapper);
        int covers = store.backfillCoverImages();
        if (covers > 0) LoggerFactory.getLogger(AppConfig.class).info("covers backfilled for {} stored items", covers);
        return store;
    }

    /**
     * Token authentication. Enabled unless {@code fetch-news.auth.enabled=false}
     * ({@code FETCH_NEWS_AUTH_ENABLED=false}); {@code FETCH_NEWS_ADMIN_TOKEN} pins the admin token.
     */
    @Bean
    public AuthService authService(FeedStore feedStore,
                                   @Value("${fetch-news.auth.enabled:true}") boolean enabled,
                                   @Value("${fetch-news.admin-token:}") String adminToken) throws SQLException {
        AuthService service = new AuthService(feedStore, enabled);
        service.bootstrap(adminToken);
        return service;
    }

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(AuthService authService) {
        FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(new AuthFilter(authService));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * AI config store — persists Ollama host/model to {@code ai-config.json}.
     */
    @Bean
    public AiConfigStore aiConfigStore(ObjectMapper objectMapper) {
        Path path = Path.of(
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
     * Article triage — picks each category's must-know and worthwhile articles in the background.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public ArticleTriage articleTriage(OllamaClient ollamaClient, FeedStore feedStore, AiConfigStore config,
                                       AppRuntime runtime, ObjectMapper objectMapper) {
        return new ArticleTriage(ollamaClient, feedStore, config, new ArticleTriage.Routes() {
            @Override public String category(String routePath) {
                return runtime.categoryOf(routePath);
            }

            @Override public String sourceLabel(String routePath) {
                RouteConfig route = runtime.routeConfigStore().get(routePath);
                return route.alias() != null ? route.alias() : SourceLabels.shortName(routePath);
            }
        }, objectMapper);
    }

    /**
     * Feishu config store — persists webhook URL and settings to {@code feishu-config.json}.
     */
    @Bean
    public FeishuConfigStore feishuConfigStore(ObjectMapper objectMapper) {
        Path path = Path.of(System.getProperty("fetch-news.feishu-config", "feishu-config.json"));
        var renamed = new HashMap<String, List<String>>();
        Topic.RENAMED.forEach((name, topics) -> renamed.put(name, topics.stream().map(Topic::label).toList()));
        return new FeishuConfigStore(path, objectMapper, renamed);
    }

    /**
     * Feishu pusher — sends new feed items to the configured webhook after each scheduled fetch.
     */
    @Bean
    public FeishuPusher feishuPusher(FeishuConfigStore feishuConfigStore, ObjectMapper objectMapper) {
        return new FeishuPusher(feishuConfigStore, objectMapper);
    }
    @Bean
    public RadarConfig radarConfig() {
        return RadarConfig.BUILT_IN;
    }

    @Bean
    public RadarService radarService(FeedStore store, RadarConfig config, AiConfigStore ai, AppRuntime runtime,
                                     ObjectMapper mapper) {
        return new RadarService(store, config, new BuiltInProfiles(mapper), ai, () -> {
            Map<String,String> categories = new HashMap<>();
            for (var route : runtime.routeConfigStore().list(runtime.routeRegistry().paths())) {
                categories.put(route.path(), runtime.categoryOf(route));
            }
            return categories;
        });
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public RadarWorker radarWorker(FeedStore store, RadarConfig config, AiConfigStore ai,
                                   OllamaClient client, ArticleExtractor extractor, ObjectMapper mapper) throws SQLException {
        return new RadarWorker(store, config, ai, client, extractor, mapper, Clock.systemUTC());
    }

    @Bean
    public RadarScheduler radarScheduler(RadarConfig config, RadarService service) {
        return new RadarScheduler(config, service, Clock.systemUTC());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public app.radar.CommunityContextService communityContextService(FeedStore store, AppRuntime runtime, ObjectMapper mapper) throws Exception {
        return new app.radar.CommunityContextService(store, runtime.fetchClient(), mapper);
    }

}
