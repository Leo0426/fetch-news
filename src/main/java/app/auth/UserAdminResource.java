package app.auth;

import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Admin section for issuing and revoking access tokens, loaded into {@code /index#users}.
 */
@Controller
@RequestMapping(value = "/index/users", produces = "text/html;charset=UTF-8")
public class UserAdminResource {
    private static final Logger log = LoggerFactory.getLogger(UserAdminResource.class);

    private final AuthService auth;
    private final TemplateEngine engine;

    public UserAdminResource(AuthService auth, TemplateEngine engine) {
        this.auth = auth;
        this.engine = engine;
    }

    @GetMapping
    @ResponseBody
    public ResponseEntity<String> list() {
        return section(null, null, null);
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @ResponseBody
    public ResponseEntity<String> create(@RequestParam(defaultValue = "") String name,
                                         @RequestParam(defaultValue = "MEMBER") String role) {
        Role parsed = "ADMIN".equals(role) ? Role.ADMIN : Role.MEMBER;
        return act(() -> {
            IssuedToken issued = auth.createUser(name, parsed);
            log.info("issued {} token for account '{}'", parsed, issued.user().name());
            return section("已创建账户“" + issued.user().name() + "”。", null, issued);
        });
    }

    @PostMapping("/{id}/token")
    @ResponseBody
    public ResponseEntity<String> resetToken(@PathVariable long id) {
        return act(() -> {
            IssuedToken issued = auth.resetToken(id);
            log.info("reset login token for account '{}'", issued.user().name());
            return section("已为“" + issued.user().name() + "”生成新令牌，旧令牌立即失效。", null, issued);
        });
    }

    @PostMapping("/{id}/feed-key")
    @ResponseBody
    public ResponseEntity<String> resetFeedKey(@PathVariable long id) {
        return act(() -> {
            UserAccount user = auth.resetFeedKey(id);
            return section("已更新“" + user.name() + "”的订阅密钥，旧订阅地址立即失效。", null, null);
        });
    }

    @PostMapping("/{id}/disabled")
    @ResponseBody
    public ResponseEntity<String> setDisabled(@PathVariable long id, @RequestParam boolean disabled) {
        return act(() -> {
            auth.setDisabled(CurrentUser.id(), id, disabled);
            return section(disabled ? "账户已停用，其令牌和订阅地址立即失效。" : "账户已启用。", null, null);
        });
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    public ResponseEntity<String> delete(@PathVariable long id) {
        return act(() -> {
            auth.delete(CurrentUser.id(), id);
            return section("账户及其收藏、已读记录已删除。", null, null);
        });
    }

    @FunctionalInterface
    private interface Action {
        ResponseEntity<String> run() throws SQLException;
    }

    private ResponseEntity<String> act(Action action) {
        if (!auth.enabled()) return section(null, "认证未启用，无法管理令牌。", null);
        try {
            return action.run();
        } catch (IllegalArgumentException e) {
            return section(null, e.getMessage(), null);
        } catch (SQLException e) {
            log.warn("account update failed: {}", e.getMessage());
            return section(null, "保存失败，请稍后重试。", null);
        }
    }

    private ResponseEntity<String> section(String message, String error, IssuedToken issued) {
        List<UserAccount> users = List.of();
        try {
            users = auth.listUsers();
        } catch (SQLException e) {
            log.warn("failed to list accounts: {}", e.getMessage());
            error = "账户列表暂时无法加载。";
        }
        Context ctx = new Context();
        ctx.setVariable("users", users);
        ctx.setVariable("currentUserId", CurrentUser.id());
        ctx.setVariable("authEnabled", auth.enabled());
        ctx.setVariable("message", message);
        ctx.setVariable("error", error);
        ctx.setVariable("issued", issued);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(engine.process("index/users", ctx));
    }
}
