package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiConnectionTest {
    @TempDir Path directory;
    @Test void probesModelListWithoutGenerationAndHandlesUnsupportedDiscovery() throws Exception {
        var status=new AtomicInteger(200);var requests=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/models",e->{
            assertEquals("GET",e.getRequestMethod());assertEquals("Bearer fake-key",e.getRequestHeaders().getFirst("Authorization"));
            requests.incrementAndGet();byte[] body="{\"data\":[{\"id\":\"custom\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(status.get(),body.length);try(var out=e.getResponseBody()){out.write(body);}
        });server.start();
        try {
            var mapper=new ObjectMapper();var configs=new AiConfigStore(directory.resolve("ai.json"),mapper);
            configs.save(new AiConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions","custom",false,"openai","fake-key",1024,false,"max_tokens"));
            var client=new OllamaClient(configs,mapper);
            assertTrue(client.checkConnection().modelAvailable());
            status.set(404);var unsupported=client.checkConnection();
            assertTrue(unsupported.serverReachable());assertFalse(unsupported.modelListSupported());assertFalse(unsupported.modelAvailable());
            assertEquals(2,requests.get());
        } finally {server.stop(0);}
    }
}
