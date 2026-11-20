package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.admin.AiSettingsResource;
import app.radar.RadarTestEnvironment;
import app.reader.ReaderSummaryResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiProviderFailureTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "400,unsupported_modality,model,文本生成接口",
            "404,model_not_found,model,模型 ID",
            "400,unsupported_parameter,temperature,temperature",
            "400,context_length_exceeded,input,上下文",
            "429,insufficient_quota,n/a,额度已用尽",
            "401,invalid_api_key,n/a,密钥",
            "403,forbidden,n/a,权限"
    })
    void explainsProviderRejectionsWithoutIncludingResponseText(int status, String code, String param,
                                                               String expected) throws Exception {
        var config = new AiConfig(null, "arbitrary", true, "openai", "SECRET", 4096,
                false, "auto", "auto", Map.of("temperature", 0.5));
        String response = mapper.writeValueAsString(Map.of("error",
                Map.of("code", code, "param", param, "message", "SECRET private prompt and error text")));
        Exception error = AiProviderFailure.from(config, status, response, mapper);
        String message = AiFailureMessages.describe(error);
        assertTrue(message.contains(expected), message);
        assertTrue(message.contains(Integer.toString(status)));
        assertFalse(message.contains("SECRET"));
        assertFalse(error.getMessage().contains("SECRET"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "200|application/json|{\"data\":[{\"object\":\"embedding\",\"embedding\":[0.25]}]}|向量",
        "200|audio/mpeg|private-response-bytes|音频",
        "200|image/png|private-response-bytes|图像",
        "400|application/json|{\"detail\":\"Use /v1/images/generations. private upstream detail\"}|图像",
        "400|text/plain|This is an embedding model. private upstream detail|向量",
        "404|application/json|{\"error\":{\"message\":\"Use /v1/audio/speech. private upstream detail\"}}|音频"
    })
    void probeAndReaderIdentifySelectedModelAndUnsupportedOutput(int status, String type, String body, String cause)
            throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            var calls = new AtomicInteger();
            String model = "my-model-alias";
            var models = new CopyOnWriteArrayList<String>();
            env.server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                models.add(mapper.readTree(exchange.getRequestBody()).path("model").textValue());
                byte[] output = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, output.length);
                try (var stream = exchange.getResponseBody()) { stream.write(output); }
            });
            String host = "http://127.0.0.1:" + env.server.getAddress().getPort() + "/v1";
            env.ai.save(new AiConfig(host, model, true, "openai", "test-key", 2048, false, "auto"));
            var client = new OllamaClient(env.ai, mapper);
            var admin = new AiSettingsResource(null, null, env.ai, client);
            var form = new AiProviderForm(null, "test", host, model, "openai", "test-key",
                    false, 2048, false, "auto", null);
            var adminMessage = Jsoup.parse(admin.testGeneration(form, model).getBody()).text();
            assertTrue(adminMessage.contains(model), adminMessage);
            assertTrue(adminMessage.contains(cause), adminMessage);
            assertTrue(adminMessage.contains("测试模型生成"), adminMessage);
            assertFalse(adminMessage.contains("private"));
            assertFalse(adminMessage.contains("test-key"));

            String link = "https://example.test/article";
            env.save(link);
            try (var summaries = new ArticleSummarizer(client, env.store, null, env.ai)) {
                var reader = new ReaderSummaryResource(env.store, summaries, env.ai);
                assertEquals(202, reader.summarize(link, "").getStatusCode().value());
                summaries.processNext();
                var progress = reader.summaryStatus(link).getBody();
                assertEquals("error", progress.state());
                assertTrue(progress.error().contains(model), progress.error());
                assertTrue(progress.error().contains(cause), progress.error());
                assertTrue(progress.error().contains("联系管理员"), progress.error());
                assertFalse(progress.error().contains("private"));
                assertFalse(progress.error().contains("test-key"));
            }
            assertEquals(2, calls.get());
            assertEquals(List.of(model, model), models);
        }
    }

    @Test
    void incompatibleModelSurfacesReasonInReaderAndStopsRadarAfterOneArticle() throws Exception {
        try (var env = new RadarTestEnvironment(directory, "{\"enabled\":true}")) {
            var calls = new AtomicInteger();
            env.server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                byte[] body = mapper.writeValueAsBytes(Map.of("error", Map.of(
                        "code", "unsupported_modality", "message", "SECRET upstream body")));
                exchange.sendResponseHeaders(400, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            String host = "http://127.0.0.1:" + env.server.getAddress().getPort() + "/v1";
            env.ai.save(new AiConfig(host, "custom-embedding", true, "openai", "SECRET", 2048, false, "auto"));
            String link = "https://example.test/first";
            env.save(link);
            env.save("https://example.test/second");
            try (var summaries = new ArticleSummarizer(new OllamaClient(env.ai, mapper), env.store, null, env.ai)) {
                var reader = new ReaderSummaryResource(env.store, summaries, env.ai);
                assertEquals(202, reader.summarize(link, "").getStatusCode().value());
                summaries.processNext();
                var progress = reader.summaryStatus(link).getBody();
                assertEquals("error", progress.state());
                assertTrue(progress.error().contains("文本生成接口"));
                assertEquals(1, calls.get());
            }
            var run = env.service.submit(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "Asia/Shanghai", "test");
            env.worker.processNext();
            var status = env.service.status(run.runId());
            assertEquals("failed", status.state());
            assertTrue(status.error().contains("文本生成接口"));
            assertEquals(2, calls.get());
        }
    }
}
