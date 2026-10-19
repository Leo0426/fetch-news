package app;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.ai.OllamaClient;
import app.feishu.FeishuConfig;
import app.feishu.FeishuConfigStore;
import app.feishu.RadarDeliveryService;
import app.radar.RadarTestEnvironment;
import app.radar.eval.EvaluationService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class RadarWorkflowPagesTest {
    @TempDir Path directory;
    @Test void rendersPersistedTasksEvaluationAndDeliveryWithoutExposingCredentials() throws Exception {
        try(var env=new RadarTestEnvironment(directory,"{\"enabled\":true}")) {
            env.save("https://example.test/1");
            var run=env.service.submit(LocalDate.now().toString(),"UTC","ui-report");env.worker.processNext();
            var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
            var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);var renderer=new RadarAdminRenderer(engine);
            var config=new FeishuConfigStore(directory.resolve("feishu.json"),env.mapper);
            config.save(new FeishuConfig("https://example.test/secret-webhook",true,1,Map.of()));
            try(var evaluation=new EvaluationService(env.store,env.ai,new OllamaClient(env.ai,env.mapper),env.mapper);
                var delivery=new RadarDeliveryService(env.store,config,env.mapper,(url,body)->true)) {
                evaluation.submit("default","ui-eval");evaluation.processNext();delivery.submit(run.runId());delivery.processNext();
                var mvc=MockMvcBuilders.standaloneSetup(new RadarTasksResource(env.store,env.service,renderer),
                        new RadarEvaluationResource(evaluation,env.store,env.ai,env.mapper,renderer),new RadarDeliveryResource(delivery,env.store,env.mapper,renderer)).build();
                for(String path:java.util.List.of("tasks","evaluation","delivery")) {
                    String html=mvc.perform(get("/index/radar/"+path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(html.length()>300,path);
                    org.junit.jupiter.api.Assertions.assertFalse(html.contains("secret-webhook"));
                }
            }
        }
    }
}
