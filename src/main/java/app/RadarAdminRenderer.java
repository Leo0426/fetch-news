package app;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component
public class RadarAdminRenderer {
    private final TemplateEngine engine;
    public RadarAdminRenderer(TemplateEngine engine) { this.engine = engine; }
    public String render(String template, Map<String, Object> values) {
        var context = new Context();
        context.setVariables(values);
        return engine.process("index/" + template, context);
    }
}
