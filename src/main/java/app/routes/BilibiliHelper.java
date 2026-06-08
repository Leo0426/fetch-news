package app.routes;

import app.CredentialConfigStore;
import app.core.CacheService;
import app.core.FetchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared Bilibili helpers: browser headers, WBI signing, MD5.
 *
 * <p>WBI signing is required by Bilibili's /x/space/wbi/* endpoints.
 * The mixin key is derived from two image URLs returned by the NAV API,
 * permuted by an index array extracted from Bilibili's header JS bundle.
 * Mixin key is cached for 24 h via CacheService.
 */
class BilibiliHelper {

    static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String NAV_URL =
            "https://api.bilibili.com/x/web-interface/nav";
    private static final String JS_URL =
            "https://s1.hdslb.com/bfs/seed/laputa-header/bili-header.umd.js";
    private static final Pattern PERM_ARRAY =
            Pattern.compile("\\[(?:\\d+,){63}\\d+\\]");
    private static final Duration WBI_TTL = Duration.ofHours(24);

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;
    private final CredentialConfigStore credStore;

    BilibiliHelper(FetchClient fetchClient, ObjectMapper objectMapper,
                   CacheService cacheService, CredentialConfigStore credStore) {
        this.fetchClient  = fetchClient;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
        this.credStore    = credStore;
    }

    /** Builds base request headers using the current global Bilibili cookie. */
    Map<String, String> baseHeaders() {
        String cookie = credStore.getBilibiliCookie();
        var h = new HashMap<String, String>();
        h.put("User-Agent", BROWSER_UA);
        h.put("Referer",    "https://www.bilibili.com");
        h.put("Origin",     "https://www.bilibili.com");
        if (cookie != null && !cookie.isBlank()) h.put("Cookie", cookie);
        return Collections.unmodifiableMap(h);
    }

    // ── per-UID cookie support ────────────────────────────────────────────────

    /**
     * Returns the cookie for a specific UID via the credential store.
     * Priority: {@code BILIBILI_COOKIE_{uid}} env var → stored per-UID cookie
     * → global cookie.
     */
    String cookieForUid(String uid) {
        return credStore.getBilibiliCookieForUid(uid);
    }

    /** Returns true if any cookie is available for the UID. */
    boolean hasCookieForUid(String uid) {
        return credStore.hasBilibiliCookieForUid(uid);
    }

    /** Builds request headers with the UID-specific cookie and the given Referer. */
    Map<String, String> headersForUid(String uid, String referer) {
        var h = new HashMap<String, String>();
        h.put("User-Agent", BROWSER_UA);
        h.put("Referer",    referer);
        h.put("Origin",     "https://www.bilibili.com");
        String cookie = cookieForUid(uid);
        if (cookie != null && !cookie.isBlank()) h.put("Cookie", cookie);
        return Collections.unmodifiableMap(h);
    }

    /** Fetches a URL with the UID-specific cookie. */
    String getForUid(String url, String uid, String referer) throws Exception {
        return fetchClient.get(url, headersForUid(uid, referer));
    }

    // ── general fetch helpers ─────────────────────────────────────────────────

    /** Returns base headers with Referer overridden to the given URL. */
    Map<String, String> refererHeaders(String referer) {
        var h = new HashMap<>(baseHeaders());
        h.put("Referer", referer);
        return Collections.unmodifiableMap(h);
    }

    /** Fetches a URL using base headers. */
    String get(String url) throws Exception {
        return fetchClient.get(url, baseHeaders());
    }

    /** Fetches a URL using base headers with an overridden Referer. */
    String get(String url, String referer) throws Exception {
        return fetchClient.get(url, refererHeaders(referer));
    }

    FetchClient fetchClient() { return fetchClient; }

    /**
     * Signs a raw query-string with WBI (appends w_rid + wts).
     * Fetches / caches the mixin key automatically.
     */
    String wbiSign(String rawParams) throws Exception {
        String mixinKey = cacheService.getDetailPage("bili:wbi-mixin-key", WBI_TTL,
                this::fetchMixinKey);

        // Sort params alphabetically, URL-encode values, compute MD5
        var map = new TreeMap<String, String>();
        for (String kv : rawParams.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) map.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        long wts = System.currentTimeMillis() / 1000;
        var sb = new StringBuilder();
        for (var e : map.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        sb.append("&wts=").append(wts);
        sb.append(mixinKey);
        String wRid = md5(sb.toString());
        return rawParams + "&w_rid=" + wRid + "&wts=" + wts;
    }

    // ── WBI key derivation ────────────────────────────────────────────────────

    private String fetchMixinKey() throws Exception {
        String navJson = fetchClient.get(NAV_URL, baseHeaders());
        JsonNode nav   = objectMapper.readTree(navJson);
        String imgUrl  = nav.path("data").path("wbi_img").path("img_url").asText();
        String subUrl  = nav.path("data").path("wbi_img").path("sub_url").asText();
        String r       = stem(imgUrl) + stem(subUrl);

        String js = fetchClient.get(JS_URL,
                Map.of("Referer", "https://space.bilibili.com/1", "User-Agent", BROWSER_UA));
        Matcher m = PERM_ARRAY.matcher(js);
        if (!m.find()) throw new IllegalStateException("WBI permutation array not found in bili-header.umd.js");

        String[] parts = m.group().replaceAll("[\\[\\] ]", "").split(",");
        var key = new StringBuilder();
        for (String part : parts) {
            int idx = Integer.parseInt(part.trim());
            if (idx < r.length()) key.append(r.charAt(idx));
        }
        return key.toString().substring(0, Math.min(32, key.length()));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** URL-encodes a string using UTF-8 (spaces become +, matching JS URLSearchParams). */
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Returns the filename stem (no extension, no path) from a URL. */
    private static String stem(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    static String md5(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        var sb = new StringBuilder(32);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Escapes HTML special characters. */
    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Returns null if the string is null or blank. */
    static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
