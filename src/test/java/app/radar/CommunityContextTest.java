package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.core.FetchClient;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommunityContextTest {
    @TempDir Path directory;
    @Test void fetchesBoundedAttributedCommentsAndRejectsUnrelatedDiscussions() throws Exception {
        try(var env=new RadarTestEnvironment(directory,"{\"enabled\":true}")) {
            var calls=new AtomicInteger();
            FetchClient client=url->{
                calls.incrementAndGet();
                if(url.endsWith("/123.json")) return """
                    {"id":123,"type":"story","title":"Real discussion","url":"https://example.test/article","kids":[1,2,3,4,5,6,7,8,9,10,11],"text":"<p>Background <a href='https://reference.test/paper'>paper</a></p>"}
                    """;
                if(url.endsWith("/2.json")) return "{\"id\":2,\"type\":\"comment\",\"deleted\":true}";
                long id=Long.parseLong(url.substring(url.lastIndexOf('/')+1,url.length()-5));
                return "{\"id\":"+id+",\"parent\":123,\"type\":\"comment\",\"by\":\"reader\",\"text\":\"<script>alert(1)</script><p>Evidence</p>\"}";
            };
            var service=new CommunityContextService(env.store,client,env.mapper);
            var result=service.fetch("https://example.test/article","123");
            assertEquals(11,calls.get());assertEquals(9,result.comments().size());
            assertEquals("Evidence",result.comments().getFirst().text());
            assertEquals("https://news.ycombinator.com/item?id=1",result.comments().getFirst().url());
            assertEquals("https://reference.test/paper",result.references().getFirst());
            assertFalse(result.background().contains("<"));
            assertThrows(IllegalArgumentException.class,()->service.fetch("https://other.test/article","123"));
            assertThrows(IllegalArgumentException.class,()->service.discussionId("https://news.ycombinator.com.evil/item?id=123"));
            service.close();
        }
    }
}
