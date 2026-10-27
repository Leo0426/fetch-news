package app.routes.bilibili;

import app.core.CredentialCheck;
import app.core.FetchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Checks Bilibili cookies: logged-in UID cookies against the nav API, the public cookie by its fields,
 * since the nav API reports every cookie without a login as signed out.
 */
public class BilibiliCookieVerifier {
    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String PUBLIC_LABEL = "B站公共 Cookie";

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;

    public BilibiliCookieVerifier(FetchClient fetchClient, ObjectMapper objectMapper) {
        this.fetchClient = fetchClient;
        this.objectMapper = objectMapper;
    }

    public CredentialCheck verifyPublic(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return CredentialCheck.failed(PUBLIC_LABEL, "未填写");
        }
        if (!cookie.contains("buvid3=")) {
            return CredentialCheck.failed(PUBLIC_LABEL, "缺少 buvid3 字段");
        }
        String fields = cookie.contains("buvid4=") ? "buvid3、buvid4" : "buvid3（建议同时填写 buvid4）";
        return CredentialCheck.ok(PUBLIC_LABEL, "格式正确，包含 " + fields + "；公共 Cookie 无需登录，B 站只在抓取时校验");
    }

    public CredentialCheck verifyLogin(String uid, String name, String cookie) {
        String label = "B站 UID " + uid + (name == null || name.isBlank() ? "" : "（" + name + "）");
        if (cookie == null || cookie.isBlank()) {
            return CredentialCheck.failed(label, "未填写");
        }
        try {
            JsonNode nav = objectMapper.readTree(fetchClient.get(NAV_URL, Map.of(
                    "User-Agent", BilibiliHelper.BROWSER_UA,
                    "Referer", "https://www.bilibili.com",
                    "Cookie", cookie)));
            JsonNode data = nav.path("data");
            if (nav.path("code").asInt(-1) != 0 || !data.path("isLogin").asBoolean()) {
                return CredentialCheck.failed(label, "未登录：SESSDATA 无效或已过期，请重新登录后复制");
            }
            String mid = data.path("mid").asText();
            String uname = data.path("uname").asText();
            if (!mid.equals(uid)) {
                return CredentialCheck.failed(label, "Cookie 属于 " + uname + "（UID " + mid + "），与填写的 UID 不一致");
            }
            return CredentialCheck.ok(label, "有效：已登录为 " + uname);
        } catch (Exception e) {
            return CredentialCheck.failed(label, "无法完成验证：" + e.getMessage());
        }
    }
}
