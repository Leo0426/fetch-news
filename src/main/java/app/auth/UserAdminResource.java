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

/** Owner-only account management in the admin console. */
@Controller
@ResponseBody
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
    public ResponseEntity<String> list() {
        return section(null, null, null, null);
    }

    @GetMapping("/{id}/access")
    public ResponseEntity<String> access(@PathVariable long id) {
        return section(id, null, null, null);
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> create(@RequestParam(defaultValue = "") String name,
                                         @RequestParam(defaultValue = "MEMBER") String role,
                                         @RequestParam(required = false) String loginMode,
                                         @RequestParam(required = false) String password) {
        if (!"MEMBER".equals(role)) return forbidden("只有初始账号可担任管理员。");
        return act(null, () -> {
            String mode = loginMode != null ? loginMode : (password == null ? "token" : "password");
            if ("password".equals(mode)) {
                var member = auth.createUser(name, password);
                return section(member.id(), "已创建成员，可使用账号名称和密码登录。", null, null);
            }
            if (!"token".equals(mode)) throw new IllegalArgumentException("请选择密码或令牌登录。");
            var issued = auth.createUser(name, Role.MEMBER);
            return section(issued.user().id(), "已创建成员及其主令牌。", null, issued);
        });
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<String> password(@PathVariable long id, @RequestParam(defaultValue = "") String password) {
        return act(id, () -> {
            auth.resetPassword(CurrentUser.id(), id, password);
            return section(id, "已重置成员密码，请将新密码交给本人。", null, null);
        });
    }

    @PostMapping("/{id}/tokens")
    public ResponseEntity<String> createToken(@PathVariable long id, @RequestParam(defaultValue = "") String name) {
        return act(id, () -> section(id, "已创建登录令牌。", null, auth.createToken(id, name)));
    }

    @DeleteMapping("/{id}/tokens/{tokenId}")
    public ResponseEntity<String> revokeToken(@PathVariable long id, @PathVariable long tokenId) {
        return act(id, () -> {
            auth.revokeToken(id, tokenId);
            return section(id, "已撤销令牌，使用该令牌的登录已失效。", null, null);
        });
    }

    @PostMapping("/{id}/token")
    public ResponseEntity<String> resetToken(@PathVariable long id) {
        return act(id, () -> section(id, "已生成新的主令牌。", null, auth.resetToken(id)));
    }

    @PostMapping("/{id}/feed-key")
    public ResponseEntity<String> resetFeedKey(@PathVariable long id) {
        return act(id, () -> {
            auth.resetFeedKey(id);
            return section(id, "已更新订阅密钥。请让成员在自己的账户页复制新地址。", null, null);
        });
    }

    @PostMapping("/{id}/disabled")
    public ResponseEntity<String> setDisabled(@PathVariable long id, @RequestParam boolean disabled) {
        return act(id, () -> {
            auth.setDisabled(CurrentUser.id(), id, disabled);
            return section(id, disabled ? "已停用账户，登录与订阅地址均已停用。" : "已启用账户。", null, null);
        });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable long id) {
        return act(id, () -> {
            auth.delete(CurrentUser.id(), id);
            return section(null, "已删除成员账户及其个人阅读记录。", null, null);
        });
    }

    @FunctionalInterface
    private interface Action {
        ResponseEntity<String> run() throws SQLException;
    }

    private ResponseEntity<String> act(Long id, Action action) {
        if (CurrentUser.id() != UserAccount.OWNER_ID) return forbidden("需要管理员权限。");
        if (!auth.enabled()) return section(null, null, "认证未启用，无法管理账户。", null);
        try {
            return action.run();
        } catch (IllegalArgumentException e) {
            return section(id, null, e.getMessage(), null);
        } catch (SQLException e) {
            log.warn("account change failed (SQL code {})", e.getErrorCode());
            return section(id, null, "保存失败，请稍后重试。", null);
        }
    }

    private ResponseEntity<String> section(Long id, String message, String error, IssuedToken issued) {
        if (CurrentUser.id() != UserAccount.OWNER_ID) return forbidden("需要管理员权限。");
        Context ctx = new Context();
        String template = "index/users";
        int status = 200;
        try {
            if (id != null && auth.enabled()) {
                var account = auth.findUser(id);
                if (account == null) {
                    status = 404;
                    error = "账户不存在。";
                } else {
                    var tokens = auth.listTokens(id);
                    ctx.setVariable("account", account);
                    ctx.setVariable("tokens", tokens);
                    ctx.setVariable("protectedPrimary", tokens.stream().anyMatch(AccountToken::protectedByEnvironment));
                    template = "index/user-access";
                }
            }
            if (template.equals("index/users"))
                ctx.setVariable("users", auth.enabled() ? auth.listUsers() : List.of());
        } catch (SQLException e) {
            log.warn("account lookup failed (SQL code {})", e.getErrorCode());
            ctx.setVariable("users", List.of());
            error = "账户信息暂时无法加载。";
        }
        ctx.setVariable("authEnabled", auth.enabled());
        ctx.setVariable("message", message);
        ctx.setVariable("error", error);
        ctx.setVariable("issued", issued);
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(engine.process(template, ctx));
    }

    private static ResponseEntity<String> forbidden(String message) {
        return ResponseEntity.status(403).header(HttpHeaders.CACHE_CONTROL, "no-store").body(message);
    }
}
