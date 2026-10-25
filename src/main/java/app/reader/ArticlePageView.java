package app.reader;

import app.ai.SummaryProgress;
import app.store.StoredArticle;
import java.util.List;

public record ArticlePageView(ReaderItem item, String author, List<String> categories,
                              ArticleContent content, boolean read, boolean bookmarked,
                              String returnUrl, String error, String originalUrl,
                              boolean aiEnabled, SummaryProgress summaryProgress) {
    public static ArticlePageView from(StoredArticle article, String returnUrl) {
        return from(article, returnUrl, false, null);
    }

    public static ArticlePageView from(StoredArticle article, String returnUrl,
                                       boolean aiEnabled, SummaryProgress progress) {
        var item = article.item();
        String summary = article.summary();
        if ((summary == null || summary.isBlank()) && progress != null) summary = progress.summary();
        SummaryProgress resolved = progress == null ? new SummaryProgress("none", null, null) : progress;
        if (summary != null && !summary.isBlank()) resolved = new SummaryProgress("success", summary, null);
        else if ("success".equals(resolved.state()))
            resolved = new SummaryProgress("unknown", null, "摘要状态暂时不可用，请重新查看状态。");
        var readerItem = new ReaderItem(article.routePath(), item.title(), item.link(), item.pubDate(),
                article.firstSeenAt(), summary, null, item.image(), null);
        return new ArticlePageView(readerItem, item.author(), item.categories() == null ? List.of() : item.categories(),
                ArticleContent.from(item.description(), article.extract(), item.link()), article.read(),
                article.bookmarked(), ArticleNavigation.returnUrl(returnUrl), null,
                ArticleNavigation.originalUrl(item.link()), aiEnabled, resolved);
    }

    public static ArticlePageView error(String message, String returnUrl, String originalUrl) {
        return new ArticlePageView(null, null, List.of(), new ArticleContent("", "empty"), false, false,
                ArticleNavigation.returnUrl(returnUrl), message, ArticleNavigation.originalUrl(originalUrl),
                false, new SummaryProgress("none", null, null));
    }
}
