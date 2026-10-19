package app.feishu;

import app.radar.RadarJson;
import app.radar.RadarRenderer;
import app.radar.RadarTypes.Report;
import app.store.FeedStore;
import app.store.FeedStore.RadarJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RadarDeliveryService implements AutoCloseable {
    @FunctionalInterface public interface Transport { boolean send(String url, String body) throws Exception; }
    private final FeedStore store;
    private final FeishuConfigStore config;
    private final ObjectMapper mapper;
    private final Transport transport;
    private final AtomicBoolean processing = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("radar-delivery").factory());
    public RadarDeliveryService(FeedStore store, FeishuConfigStore config, ObjectMapper mapper) throws Exception {
        this(store,config,mapper,httpTransport(mapper));
    }
    public RadarDeliveryService(FeedStore store, FeishuConfigStore config, ObjectMapper mapper, Transport transport) throws Exception {
        this.store=store;this.config=config;this.mapper=mapper;this.transport=transport;
        if(store!=null) store.recoverRadarJobs("delivery");
    }
    public void start() { executor.scheduleWithFixedDelay(this::tick,0,5,TimeUnit.SECONDS); }
    private void tick() {
        try { scheduleAutomatic(); } catch(Exception ignored) { }
        processNext();
    }
    public RadarJob policy() throws Exception {
        if(store==null) throw new IllegalStateException("Storage unavailable");
        return store.createRadarJob("delivery-policy","global","{\"enabled\":false,\"since\":0}");
    }
    public synchronized void savePolicy(boolean enabled) throws Exception {
        var policy=policy();var data=(ObjectNode)mapper.readTree(policy.payload());
        if(enabled && !data.path("enabled").asBoolean()) data.put("since",System.currentTimeMillis());
        data.put("enabled",enabled);store.updateRadarJob(policy.id(),"success",mapper.writeValueAsString(data));
    }
    public void scheduleAutomatic() throws Exception {
        if(store==null || !config.get().enabled() || config.get().webhookUrl().isBlank()) return;
        var data=mapper.readTree(policy().payload());
        if(!data.path("enabled").asBoolean()) return;
        for(var run:store.listRadarRuns(100)) {
            if(run.status().state().equals("success") && run.createdAt()>=data.path("since").asLong()) submit(run.status().runId());
        }
    }
    public synchronized RadarJob submit(String runId) throws Exception {
        String target=target();
        Report report=store.loadRadarReport(runId);
        if(report==null) throw new IllegalArgumentException("Saved report required");
        var data=mapper.createObjectNode();data.put("runId",runId);data.put("targetHash",RadarJson.hash(target));data.put("nextPage",0);
        data.set("pages",mapper.valueToTree(pages(report,Math.clamp(config.get().maxItemsPerPush(),1,5))));
        return store.createRadarJob("delivery",runId+":"+RadarJson.hash(target),mapper.writeValueAsString(data));
    }
    public synchronized void retry(String id, boolean confirmUncertain) throws Exception {
        var job=store.loadRadarJob(id);
        if(job==null || !job.kind().equals("delivery")) throw new IllegalArgumentException("Delivery not found");
        if(!List.of("failed","paused").contains(job.state())) throw new IllegalStateException("Delivery is not retryable");
        var data=(ObjectNode)mapper.readTree(job.payload());
        if(data.has("awaitingPage") && !confirmUncertain) throw new IllegalStateException("Page delivery is uncertain; confirm possible duplicate before retry");
        if(!RadarJson.hash(target()).equals(data.path("targetHash").asText())) throw new IllegalStateException("Restore original webhook before retry");
        data.remove("error");data.remove("awaitingPage");store.updateRadarJob(id,"queued",mapper.writeValueAsString(data));
    }
    public void processNext() {
        if(store==null || !config.get().enabled() || !processing.compareAndSet(false,true)) return;
        RadarJob job=null;ObjectNode data=null;
        try {
            job=store.claimRadarJob("delivery");if(job==null) return;
            data=(ObjectNode)mapper.readTree(job.payload());
            int next=data.path("nextPage").asInt();
            for(int page=next;page<data.path("pages").size();page++) {
                String target=target();
                if(!RadarJson.hash(target).equals(data.path("targetHash").asText())) throw new IllegalStateException("Webhook changed; restore original destination");
                data.put("awaitingPage",page);save(job.id(),"running",data);
                boolean accepted=transport.send(target,data.path("pages").path(page).asText());
                data.remove("awaitingPage");
                if(!accepted) { data.put("error","Webhook rejected this page; previous accepted pages are retained.");save(job.id(),"failed",data);return; }
                data.put("nextPage",page+1);save(job.id(),"running",data);
            }
            save(job.id(),"success",data);
        } catch(Exception failure) {
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if(job!=null && data!=null) try {
                data.put("error",data.has("awaitingPage") ? "Delivery result unknown. Retry may duplicate the pending page." : "Delivery paused. Enable Feishu and restore the original webhook before retry.");
                save(job.id(),"paused",data);
            } catch(Exception ignored) { }
        } finally { processing.set(false); }
    }
    private String target() {
        String url=config.get().webhookUrl();
        if(!config.get().enabled() || RadarRenderer.safeUrl(url).isEmpty()) throw new IllegalStateException("Enable Feishu and configure its default webhook first");
        return url;
    }
    private void save(String id,String state,ObjectNode data) throws Exception { store.updateRadarJob(id,state,mapper.writeValueAsString(data)); }
    private List<String> pages(Report report,int pageSize) throws Exception {
        List<String> result=new ArrayList<>();
        int count=Math.max(1,(report.stories().size()+pageSize-1)/pageSize);
        for(int page=0;page<count;page++) {
            List<Map<String,Object>> elements=new ArrayList<>();
            if(report.stories().isEmpty()) elements.add(section("没有符合筛选标准的内容。"));
            for(var story:report.stories().subList(Math.min(page*pageSize,report.stories().size()),Math.min((page+1)*pageSize,report.stories().size()))) {
                var item=story.event().primary();
                String title=plain(report.titles().getOrDefault(item.link(),item.summary()),200);
                String url=RadarRenderer.safeUrl(item.link()).replace("(","%28").replace(")","%29");
                StringBuilder text=new StringBuilder(title).append(" · ").append(item.score()).append(" 分\n");
                text.append(plain(item.summary(),500)).append("\n");
                int remaining=1200;
                for(var block:story.blocks().entrySet()) {
                    String value=plain(String.join("\n",block.getValue()),Math.min(remaining,400));
                    if(remaining<=0) break;
                    text.append(plain(block.getKey(),80)).append(": ").append(value).append("\n");remaining-=value.length();
                }
                if(story.error()!=null) text.append("部分内容生成失败\n");
                if(!url.isEmpty()) text.append("[查看原文](").append(url).append(")");
                elements.add(section(text.toString()));
            }
            String title="精选日报 · "+report.date()+" · "+(page+1)+"/"+count+(report.failures()>0 || report.omitted()>0 ? " · 部分结果" : "");
            result.add(mapper.writeValueAsString(Map.of("msg_type","interactive","card",Map.of("header",Map.of("title",Map.of("tag","plain_text","content",title)),"elements",elements))));
        }
        return result;
    }
    private Map<String,Object> section(String text) { return Map.of("tag","div","text",Map.of("tag","lark_md","content",text)); }
    private String plain(String text,int limit) {
        String value=RadarJson.truncate(RadarJson.article(text),Math.max(0,limit));
        for(String c:new String[]{"\\","[","]","(",")","*","_","`","#","!","|","<",">"}) value=value.replace(c,"\\"+c);
        return value;
    }
    private static Transport httpTransport(ObjectMapper mapper) {
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (url,body)->{
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new java.io.IOException("Webhook response unknown");
            var node=mapper.readTree(response.body());
            var code=node.has("code") ? node.path("code") : node.path("StatusCode");
            if(!code.isIntegralNumber()) throw new java.io.IOException("Webhook acknowledgement invalid");
            return code.asInt()==0;
        };
    }
    @Override public void close() { executor.shutdownNow(); }
}
