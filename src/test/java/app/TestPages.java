package app;

import app.core.CacheService;
import app.core.Route;
import app.core.RouteConfigStore;
import app.core.RouteRegistry;
import java.util.List;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Shared setup for tests that build pages or a runtime without starting Spring. */
public final class TestPages {
    private TestPages() {}

    /** The application's Thymeleaf templates, loaded from the classpath as in production. */
    public static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /** A runtime serving only {@code routes}, with route settings kept in {@code configs}. */
    public static AppRuntime runtime(RouteConfigStore configs, Route... routes) {
        return runtime(configs, new RouteRegistry(List.of(routes)));
    }

    public static AppRuntime runtime(RouteConfigStore configs, RouteRegistry registry) {
        return new AppRuntime(new CacheService(), registry, configs);
    }
}
