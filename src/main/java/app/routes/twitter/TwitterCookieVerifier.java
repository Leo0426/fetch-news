package app.routes.twitter;

import app.core.CacheService;
import app.core.CredentialCheck;
import app.core.FetchClient;
import app.core.RouteException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Checks an X login cookie by making the same profile lookup the timeline routes start with.
 */
public class TwitterCookieVerifier {
    static final String LABEL = "X 登录 Cookie";
    private static final String PROBE_ACCOUNT = "X";

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;

    public TwitterCookieVerifier(FetchClient fetchClient, ObjectMapper objectMapper, CacheService cacheService) {
        this.fetchClient = fetchClient;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
    }

    public CredentialCheck verify(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return CredentialCheck.failed(LABEL, "未填写");
        }
        if (!hasField(cookie, "auth_token") || TwitterHelper.extractCt0(cookie).isEmpty()) {
            return CredentialCheck.failed(LABEL, "缺少 auth_token 或 ct0 字段，请按下方说明复制两个字段");
        }
        var helper = new TwitterHelper(fetchClient, objectMapper, cacheService, () -> cookie);
        try {
            if (helper.getUserByScreenName(PROBE_ACCOUNT).path("legacy").isMissingNode()) {
                return CredentialCheck.failed(LABEL, "X 返回了空结果，Cookie 可能已失效，请重新登录后复制");
            }
            return CredentialCheck.ok(LABEL, "有效：已用该 Cookie 读取 @" + PROBE_ACCOUNT + " 的资料");
        } catch (RouteException e) {
            return CredentialCheck.failed(LABEL, "X 拒绝了请求（" + e.getMessage() + "），Cookie 可能已过期或账号已登出");
        } catch (Exception e) {
            return CredentialCheck.failed(LABEL, "无法完成验证：" + e.getMessage());
        }
    }

    static boolean hasField(String cookie, String name) {
        for (String part : cookie.split(";")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length == 2 && name.equals(kv[0].strip()) && !kv[1].isBlank()) return true;
        }
        return false;
    }
}
