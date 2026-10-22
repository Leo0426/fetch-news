package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiProtocolTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void openAiUsesConfiguredPrefixBearerAndOptionalJsonMode() throws Exception {
        var received=new AtomicReference<JsonNode>();var path=new AtomicReference<String>();var authorization=new AtomicReference<String>();
        var output=new AtomicReference<>("{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}");
        HttpServer server=server(output,(e,input)->{received.set(input);path.set(e.getRequestURI().getPath());authorization.set(e.getRequestHeaders().getFirst("Authorization"));});
        try {
            var configs=new AiConfigStore(directory.resolve("openai.json"),mapper);
            var config=new AiConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/relay/v1/","custom-model",true,"openai","test-secret",2048,true,"max_completion_tokens");
            configs.save(config);var client=new OllamaClient(configs,mapper);
            assertEquals("{\"ok\":true}",client.generateJson("Return JSON",config).text());
            assertEquals("/relay/v1/chat/completions",path.get());assertEquals("Bearer test-secret",authorization.get());
            assertEquals("custom-model",received.get().path("model").asText());assertEquals(2048,received.get().path("max_completion_tokens").asInt());
            assertEquals("json_object",received.get().path("response_format").path("type").asText());assertFalse(received.get().path("stream").asBoolean());
            assertEquals("user",received.get().path("messages").get(0).path("role").asText());
            configs.save(new AiConfig(config.host(),config.model(),true,"openai",config.apiKey(),2048,false,"max_tokens"));
            client.generateJson("Return JSON",configs.get());assertFalse(received.get().has("response_format"));assertEquals(2048,received.get().path("max_tokens").asInt());
            output.set("{\"choices\":[{\"message\":{\"content\":\"cut\"},\"finish_reason\":\"length\"}]}");
            assertEquals("AI output token limit reached", assertThrows(Exception.class,()->client.generate("test")).getMessage());
            output.set("{\"choices\":[{\"message\":{\"refusal\":\"private provider detail\"},\"finish_reason\":\"stop\"}]}");
            assertEquals("AI response refused", assertThrows(Exception.class,()->client.generate("test")).getMessage());
            output.set("{\"choices\":[{\"message\":{\"tool_calls\":[{}]},\"finish_reason\":\"tool_calls\"}]}");
            assertEquals("AI response requires unsupported tools", assertThrows(Exception.class,()->client.generate("test")).getMessage());
        } finally { server.stop(0); }
    }
    @Test void anthropicUsesMessagesHeadersAndOnlyTextBlocks() throws Exception {
        var received=new AtomicReference<JsonNode>();var headers=new AtomicReference<Map<String,String>>();
        var output=new AtomicReference<>("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"},{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"}],\"stop_reason\":\"end_turn\"}");
        HttpServer server=server(output,(e,input)->{received.set(input);headers.set(Map.of("key",e.getRequestHeaders().getFirst("x-api-key"),"version",e.getRequestHeaders().getFirst("anthropic-version"),"path",e.getRequestURI().getPath()));});
        try {
            var configs=new AiConfigStore(directory.resolve("anthropic.json"),mapper);
            configs.save(new AiConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/messages","claude-custom",true,"anthropic","anthropic-secret",1024,false,"max_tokens"));
            var client=new OllamaClient(configs,mapper);
            assertEquals("first\nsecond",client.generate("question"));assertEquals("/v1/messages",headers.get().get("path"));
            assertEquals("anthropic-secret",headers.get().get("key"));assertEquals("2023-06-01",headers.get().get("version"));
            assertEquals(1024,received.get().path("max_tokens").asInt());assertFalse(received.get().has("response_format"));
            output.set("{\"content\":[{\"type\":\"text\",\"text\":\"cut\"}],\"stop_reason\":\"max_tokens\"}");assertThrows(Exception.class,()->client.generate("test"));
            output.set("{\"error\":{\"message\":\"anthropic-secret\"}}");
            Exception failure=assertThrows(Exception.class,()->client.generate("test"));assertFalse(failure.toString().contains("anthropic-secret"));
        } finally { server.stop(0); }
    }

    @Test void protocolSwitchIsDetectedBeforeAnyRequestWhenHostAndModelStayTheSame() {
        var configs=new AiConfigStore(directory.resolve("identity.json"),mapper);
        var previous=new AiConfig("http://127.0.0.1:1","same",true);
        configs.save(new AiConfig(previous.host(),previous.model(),true,"openai","key",4096,true,"max_tokens"));
        var client=new OllamaClient(configs,mapper);
        assertThrows(IllegalStateException.class,()->client.generateJson("JSON",previous));
    }

    private HttpServer server(AtomicReference<String> output,RequestObserver observer) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            try {
                var node=mapper.readTree(e.getRequestBody());observer.accept(e,node);
                byte[] body=output.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}
            } catch(Exception failure) { e.sendResponseHeaders(500,-1);e.close(); }
        });server.start();return server;
    }
    @FunctionalInterface interface RequestObserver { void accept(com.sun.net.httpserver.HttpExchange exchange,JsonNode input) throws Exception; }
}
