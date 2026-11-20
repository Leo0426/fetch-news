package app.admin;

import app.auth.UserAccount;
import app.store.FeedStore;
import app.store.UserContentStore.Section;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Controller
@ResponseBody
@RequestMapping(value = "/index/users/{id}/content", produces = "text/html;charset=UTF-8")
public class UserContentResource {
    private final FeedStore store;
    private final TemplateEngine engine;

    public UserContentResource(FeedStore store, TemplateEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    @GetMapping
    public ResponseEntity<String> page(@PathVariable long id, @RequestParam(defaultValue = "bookmarks") String tab,
                                       @RequestParam(defaultValue = "1") long page) throws SQLException {
        return render(id, section(tab), page, null, null);
    }

    @PostMapping(value = "/{tab}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> change(@PathVariable long id, @PathVariable String tab,
                                         @RequestParam String action, @RequestParam String link,
                                         @RequestParam(defaultValue = "") String kind,
                                         @RequestParam(defaultValue = "1") long page) throws SQLException {
        if (store == null) return unavailable();
        Section section = section(tab);
        try {
            store.userContent().change(id, section, action, link, kind);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return render(id, section, page, null, e.getMessage());
        }
        return render(id, section, "add".equals(action) ? 1 : page, "记录已更新。", null);
    }

    private static ResponseEntity<String> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body("文章库暂时不可用。");
    }

    private static Section section(String tab) {
        return Section.fromId(tab).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容类别无效。"));
    }

    private ResponseEntity<String> render(long id, Section section, long page, String message, String error)
            throws SQLException {
        if (store == null) return unavailable();
        UserAccount user = store.accounts().findUser(id);
        if (user == null) return ResponseEntity.notFound().build();
        Context ctx = new Context();
        ctx.setVariable("user", user);
        ctx.setVariable("base", "/index/users/" + id + "/content");
        ctx.setVariable("section", section);
        ctx.setVariable("sections", Section.values());
        ctx.setVariable("page", store.userContent().page(id, section, page));
        ctx.setVariable("message", message);
        ctx.setVariable("error", error);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(engine.process("index/user-content", ctx));
    }
}
