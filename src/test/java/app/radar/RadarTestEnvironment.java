package app.radar;

import app.ai.AiConfig;
import app.ai.AiConfigStore;
import app.ai.OllamaClient;
import app.core.FeedItem;
import app.store.FeedStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class RadarTestEnvironment implements AutoCloseable {
    public final ObjectMapper mapper=new ObjectMapper();
    public final FeedStore store;
    public final AiConfigStore ai;
    public final RadarConfigStore config;
    public final ProfileStore profiles;
    public final RadarService service;
    public final RadarWorker worker;
    public final HttpServer server;
    public final AtomicInteger requests=new AtomicInteger();
    public volatile String blocksResponse="{\"blocks\":{\"summary\":[\"摘要\"],\"importance\":[\"价值\"]}}";
    public volatile Runnable onBlocks=()->{};
    public volatile String scoreResponse="{\"score\":8,\"reason\":\"Useful\",\"summary\":\"Evidence\",\"tags\":[]}";

    public volatile java.util.function.Function<String,String> scoring=p->scoreResponse;

    public RadarTestEnvironment(Path directory,String configJson) throws Exception {
        store=new FeedStore(directory.resolve("feed.db"),mapper);
        ai=new AiConfigStore(directory.resolve("ai.json"),mapper);
        config=new RadarConfigStore(directory.resolve("radar.json"),mapper);
        config.save(mapper.readValue(configJson,RadarConfig.class));
        profiles=new ProfileStore(directory.resolve("profiles"),mapper);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/generate",e->{
            requests.incrementAndGet();
            var input=mapper.readTree(e.getRequestBody());
            String prompt=input.path("prompt").asText();
            String output=prompt.startsWith("TASK: score") ? scoring.apply(prompt) : prompt.startsWith("TASK: dedup") ? "{\"match\":\"distinct\"}" : prompt.startsWith("TASK: route") ? "{\"profile\":\"tech-news\"}" : blocksResponse;
            if(prompt.startsWith("TASK: blocks")) onBlocks.run();
            byte[] body=mapper.writeValueAsBytes(Map.of("response",output));
            e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}
        }); server.start();
        ai.save(new AiConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fake",true));
        var client=new OllamaClient(ai,mapper);
        service=new RadarService(store,config,profiles,ai,()->Map.of("/a","AI"));
        worker=new RadarWorker(store,config,ai,client,null,mapper,Clock.systemUTC());
    }
    public void save(String link) throws Exception {
        store.saveItems("/a",List.of(new FeedItem("Technical event "+link,link,"Concrete evidence and engineering details. ".repeat(20),null,null,List.of())));
    }
    @Override public void close() throws Exception { worker.close(); server.stop(0); store.close(); }
}
