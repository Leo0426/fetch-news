package app.reader;

import app.store.StoredArticle;
import java.util.List;

public record ArticlePageView(ReaderItem item, String author, List<String> categories,
                              ArticleContent content, boolean read, boolean bookmarked,
                              String returnUrl, String error, String originalUrl) {
    public static ArticlePageView from(StoredArticle article, String returnUrl) {
        var item = article.item();
        var readerItem = new ReaderItem(article.routePath(), item.title(), item.link(), item.pubDate(),
                article.firstSeenAt(), article.summary(), null);
        return new ArticlePageView(readerItem, item.author(), item.categories() == null ? List.of() : item.categories(),
                ArticleContent.from(item.description(), article.extract(), item.link()), article.read(),
                article.bookmarked(), ArticleNavigation.returnUrl(returnUrl), null,
                ArticleNavigation.originalUrl(item.link()));
    }

    public static ArticlePageView error(String message, String returnUrl, String originalUrl) {
        return new ArticlePageView(null, null, List.of(), new ArticleContent("", "empty"), false, false,
                ArticleNavigation.returnUrl(returnUrl), message, ArticleNavigation.originalUrl(originalUrl));
    }
}
