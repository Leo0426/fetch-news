package app.ai;

import app.auth.AuthTokens;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class AiGenerationTransport {
    private static final int MAX_ATTEMPTS = 8;
    private final Cache<Key, AiGenerationPlan> learned = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(Duration.ofHours(1)).build();
    private final HttpClient http;
    private final ObjectMapper mapper;

    AiGenerationTransport(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    OllamaClient.Generation generate(AiConfig config, String prompt, boolean json,
            OllamaClient.BeforeGeneration before, OllamaClient.AfterGeneration after) throws Exception {
        var key = new Key(config.identity(), AuthTokens.hash(config.apiKey()));
        AiGenerationPlan plan = learned.getIfPresent(key);
        if (plan == null) plan = AiGenerationPlan.initial(config);
        var tried = new HashSet<AiGenerationPlan>();
        AiProviderFailure endpointFailure = null;
        while (true) {
            tried.add(plan);
            var protocol = plan.protocol();
            var body = plan.body(protocol, config, prompt, json, mapper);
            HttpRequest request;
            try {
                request = plan.request(protocol, config, body, mapper);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid AI endpoint or authentication header");
            }
            if (before != null) before.before();
            long started = System.nanoTime();
            GenerationMetrics metrics = null;
            try {
                HttpResponse<String> response = http.send(request, info -> {
                    String type = info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    if (info.statusCode() / 100 == 2 && NonTextOutput.contentType(type) != null)
                        return HttpResponse.BodySubscribers.replacing("");
                    return HttpResponse.BodyHandlers.ofString().apply(info);
                });
                if (response.statusCode() / 100 != 2) {
                    var next = AiRequestRejection.next(config, plan, body, response.statusCode(),
                            response.body(), mapper);
                    if (next != null && tried.size() < MAX_ATTEMPTS && !tried.contains(next)) {
                        if (!plan.adapter().equals(next.adapter()) && endpointFailure == null)
                            endpointFailure = AiProviderFailure.wrongEndpoint(config, response.statusCode());
                        plan = next;
                        continue;
                    }
                    int status = response.statusCode();
                    var failure = AiProviderFailure.from(config, status, response.body(), mapper);
                    if (endpointFailure != null && !failure.hasSpecificReason()
                            && (status == 400 || status == 404 || status == 405 || status == 422))
                        throw endpointFailure;
                    throw failure;
                }
                JsonNode node = decode(config, plan.adapter(), response);
                if (node != null && node.hasNonNull("error"))
                    throw AiProviderFailure.fromEnvelope(config, node.get("error"));
                if (node == null || !node.isObject())
                    throw new IllegalStateException("AI platform returned an error response");
                metrics = GenerationMetrics.from(plan.adapter(), node, elapsed(started));
                NonTextOutput kind = NonTextOutput.result(node);
                if (kind != null) throw AiProviderFailure.nonText(config, kind);
                String text = ModelOutput.normalize(protocol.text(node), json);
                if (text.isBlank()) {
                    kind = NonTextOutput.media(node);
                    if (kind != null) throw AiProviderFailure.nonText(config, kind);
                    throw new IllegalStateException("AI platform returned no text");
                }
                learned.put(key, plan);
                return new OllamaClient.Generation(text, config.model(), metrics);
            } finally {
                if (after != null)
                    after.after(metrics == null ? new GenerationMetrics(null, null, elapsed(started)) : metrics);
            }
        }
    }

    private JsonNode decode(AiConfig config, String adapter, HttpResponse<String> response) {
        String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0]
                .strip().toLowerCase(Locale.ROOT);
        NonTextOutput kind = NonTextOutput.contentType(type);
        if (kind != null) throw AiProviderFailure.nonText(config, kind);
        if (type.equals("text/event-stream") || type.equals("application/x-ndjson")
                || type.equals("application/ndjson"))
            return AiStreams.decode(adapter, response.body(), mapper, config);
        try {
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("AI platform returned invalid JSON");
        }
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record Key(List<Object> identity, String credentialHash) {}
}
