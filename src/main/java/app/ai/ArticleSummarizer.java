package app.ai;

import app.core.FeedItem;
import app.store.FeedStore;
import app.store.SummaryStatus;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArticleSummarizer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ArticleSummarizer.class);
    private final OllamaClient ollama;
    private final FeedStore store;
    private final ArticleExtractor extractor;
    private final AiConfigStore config;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("article-summary").factory());
    private final AtomicBoolean processing = new AtomicBoolean();
    private boolean started;
    private volatile boolean closed;

    public ArticleSummarizer(OllamaClient ollama, FeedStore store,
                             ArticleExtractor extractor, AiConfigStore config) throws SQLException {
        this.ollama = ollama;
        this.store = store;
        this.extractor = extractor;
        this.config = config;
        if (store != null) store.recoverInterruptedSummaries(Instant.now());
    }

    public synchronized void start() {
        if (started || closed) return;
        started = true;
        worker.scheduleWithFixedDelay(this::processNext, 0, 1, TimeUnit.SECONDS);
    }

    public synchronized SummaryProgress request(String link) throws SQLException {
        checkAvailable();
        if (store.loadItem(link) == null) throw new NoSuchElementException("Article not found");
        store.enqueueSummary(link, Instant.now(), true);
        return progress(link);
    }

    public synchronized int enqueue(List<FeedItem> items) throws SQLException {
        checkAvailable();
        int accepted = 0;
        for (FeedItem item : items) {
            if (closed || !config.get().enabled()) break;
            if (item.link() != null && !item.link().isBlank()
                    && store.enqueueSummary(item.link(), Instant.now(), false)) accepted++;
        }
        return accepted;
    }

    public SummaryProgress progress(String link) throws SQLException {
        if (store == null) throw new IllegalStateException("Article storage unavailable");
        if (store.loadItem(link) == null) throw new NoSuchElementException("Article not found");
        String summary = store.loadSummary(link);
        if (summary != null && !summary.isBlank()) return new SummaryProgress("success", summary, null);
        SummaryStatus status = store.loadSummaryStatuses(List.of(link)).get(link);
        if (status == null) return new SummaryProgress("none", null, null);
        // Completion may commit after the first cache read and before the status read.
        if (status.success()) {
            summary = store.loadSummary(link);
            if (summary == null || summary.isBlank()) throw new SQLException("Completed summary is missing");
            return new SummaryProgress("success", summary, null);
        }
        return new SummaryProgress(status.status(), null, status.errorMessage());
    }

    private void checkAvailable() {
        if (closed) throw new IllegalStateException("Summary worker is closed");
        if (store == null) throw new IllegalStateException("Article storage unavailable");
        if (config == null || !config.get().enabled()) throw new IllegalStateException("AI is disabled");
    }

    public void processNext() {
        if (closed || store == null || config == null || !config.get().enabled()
                || !processing.compareAndSet(false, true)) return;
        try {
            processClaimedItem();
        } catch (Exception e) {
            log.warn("summary queue unavailable: {}", e.toString());
        } finally {
            processing.set(false);
        }
    }

    private void processClaimedItem() throws Exception {
        Instant startedAt = Instant.now();
        String model = ollama.model();
        FeedItem item = store.claimNextSummary(startedAt, model);
        if (item == null) return;
        try {
            checkInterrupted();
            String description = item.description() == null ? "" : Jsoup.parse(item.description()).text().strip();
            String content = description.codePointCount(0, description.length()) >= 200
                    ? description : extractor.extract(item);
            checkInterrupted();
            if (content == null || content.isBlank()) throw new IllegalStateException("No article content available");
            OllamaClient.Generation generation = ollama.generateWithMetadata(buildPrompt(
                    item.title(), ArticleExtractor.truncate(content, 3000)));
            model = generation.model();
            checkInterrupted();
            String summary = generation.text() == null ? "" : generation.text().strip();
            if (summary.isEmpty()) throw new IllegalStateException("Ollama returned an empty summary");
            store.saveSummary(item.link(), summary, model);
            store.markSummaryFinished(item.link(), startedAt, Instant.now(), model, null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String error = e.getMessage();
            if (error == null || error.isBlank()) error = e.getClass().getSimpleName();
            store.markSummaryFinished(item.link(), startedAt, Instant.now(), model, error);
            log.warn("summary failed: {}", e.toString());
        }
    }

    private void checkInterrupted() throws InterruptedException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Summary task interrupted by shutdown");
        }
    }

    private static String buildPrompt(String title, String content) {
        return """
                你是一位新闻编辑。请用简体中文为以下文章写 2-3 句摘要，抓住核心事实和关键观点，简洁客观。
                不要以"本文"或"这篇文章"开头，直接陈述内容。

                标题：%s

                内容：
                %s
                """.formatted(title == null ? "" : title, content);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            worker.shutdownNow();
        }
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
