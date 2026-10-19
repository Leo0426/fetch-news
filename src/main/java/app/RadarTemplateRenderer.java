package app;

import app.reader.RadarPageView;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component
public class RadarTemplateRenderer {
    private final TemplateEngine engine;
    public RadarTemplateRenderer(TemplateEngine engine) { this.engine=engine; }
    public String render(RadarPageView page) {
        var context=new Context(); context.setVariable("page",page);
        return engine.process("reader/radar",context);
    }
}
