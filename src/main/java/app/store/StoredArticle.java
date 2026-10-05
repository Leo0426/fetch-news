package app.store;

import app.core.FeedItem;
import java.time.Instant;

public record StoredArticle(FeedItem item, String routePath, Instant firstSeenAt,
                            String summary, String extract, boolean read, boolean bookmarked) {}
