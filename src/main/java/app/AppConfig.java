package app;

import app.ai.AiConfigStore;
import app.ai.ArticleExtractor;
import app.ai.ArticleSummarizer;
import app.ai.OllamaClient;
import app.store.FeedStore;
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
    public ArticleExtractor articleExtractor(OllamaClient ollamaClient, FeedStore feedStore) {
        return new ArticleExtractor(ollamaClient, feedStore);
    }

    /**
     * Article summarizer — generates 2-3 sentence Chinese summaries for reader cards.
     */
    @Bean
    public ArticleSummarizer articleSummarizer(OllamaClient ollamaClient, FeedStore feedStore) {
        return new ArticleSummarizer(ollamaClient, feedStore);
    }
}
