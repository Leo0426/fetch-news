package app.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class DefaultFetchClientBodyTest {
    @Test
    void requestsGzipAndDecodesItWithTheDeclaredCharset() throws Exception {
        String text = "<rss>中文标题</rss>";
        try (TestServer server = new TestServer(exchange -> {
            try (exchange) {
                byte[] body = gzip(text.getBytes(Charset.forName("GBK")));
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=\"GBK\"");
                String accepted = exchange.getRequestHeaders().getFirst("Accept-Encoding");
                exchange.getResponseHeaders().set("X-Accept-Encoding", accepted);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        })) {
            FetchResponse response = client(Duration.ofSeconds(5)).fetch(server.url(), Map.of());

            assertEquals(text, response.body());
            assertEquals("gzip", response.header("X-Accept-Encoding"));
        }
    }

    @Test
    void rejectsBodiesOverTheLimitInsteadOfBufferingThem() throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                byte[] chunk = new byte[1 << 20];
                for (int i = 0; i <= DefaultFetchClient.MAX_BODY_BYTES / chunk.length; i++) out.write(chunk);
            } catch (IOException clientGaveUp) {
                // expected once the client stops reading
            }
        })) {
            assertThrows(IOException.class, () -> client(Duration.ofSeconds(5)).fetch(server.url(), Map.of()));
        }
    }

    @Test
    void stalledBodyTimesOutAndReleasesTheHostPermit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (TestServer server = new TestServer(exchange -> {
            try (exchange) {
                if (exchange.getRequestURI().getQuery() != null) {
                    byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    exchange.getResponseBody().write(ok);
                    return;
                }
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })) {
            DefaultFetchClient client = client(Duration.ofMillis(300));
            long started = System.nanoTime();
            for (int i = 0; i < 2; i++) {
                assertThrows(IOException.class, () -> client.fetch(server.url(), Map.of()));
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
            // Both permits for the host were held by stalled requests; they must be free again.
            assertEquals("ok", client.fetch(server.url() + "?next", Map.of()).body());
        } finally {
            release.countDown();
        }
    }

    private static DefaultFetchClient client(Duration timeout) {
        return new DefaultFetchClient(timeout, "fetch-news-test");
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private TestServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/feed", handler);
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/feed";
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
