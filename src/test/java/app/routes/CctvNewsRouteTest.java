package app.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.core.CacheService;
import app.core.FetchClient;
import app.core.RouteContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CctvNewsRouteTest {
    @Test
    void retainsListCoverWhetherArticleDetailsSucceedOrFail() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            FetchClient client = url -> {
                if (url.contains(".jsonp")) return """
                        callback({"data":{"list":[{"title":"新闻标题", "url":"https://news.cctv.com/ART123.shtml",
                          "image":"//cdn.example.com/cover.jpg"}]}});
                        """;
                if (fail) throw new IllegalStateException("Detail unavailable");
                return "{\"article_content\":\"<p>正文</p><img>\"}";
            };
            var route = new CctvNewsRoute(client, new CacheService(), new ObjectMapper());
            var item = route.handle(new RouteContext("/cctv/news/news", Map.of("category", "news"), Map.of()))
                    .items().getFirst();
            assertEquals("https://cdn.example.com/cover.jpg", item.image());
            assertEquals(fail ? null : "<p>正文</p><img>", item.description());
        }
    }
}
