package app.routes;

import app.core.FetchClient;
import app.core.RouteContext;
import app.store.FeedStore;

public class OpenAiNewsRoute extends GenericRssRoute {
    public OpenAiNewsRoute(FetchClient client, FeedStore store) {
        super(client, store);
    }

    @Override
    public String feedUrl(RouteContext context) {
        return "https://openai.com/news/rss.xml";
    }
}
