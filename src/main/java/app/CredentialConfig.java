package app;

import java.util.Map;

/**
 * Persisted credential configuration for third-party API access.
 *
 * <p>Environment variables take precedence over stored values:
 * {@code BILIBILI_COOKIE}, {@code BILIBILI_COOKIE_{uid}}, {@code TWITTER_COOKIE}.
 *
 * @param bilibiliCookie       global Bilibili cookie (buvid3/buvid4, no login required)
 * @param bilibiliUidCookies   per-UID Bilibili cookies keyed by UID string
 * @param bilibiliUidNames     optional display names for each UID, keyed by UID string
 * @param twitterCookie        Twitter/X cookie (auth_token + ct0)
 */
public record CredentialConfig(
        String bilibiliCookie,
        Map<String, String> bilibiliUidCookies,
        Map<String, String> bilibiliUidNames,
        String twitterCookie) {

    public CredentialConfig {
        if (bilibiliCookie     == null) bilibiliCookie = "";
        if (bilibiliUidCookies == null) bilibiliUidCookies = Map.of();
        if (bilibiliUidNames   == null) bilibiliUidNames = Map.of();
        if (twitterCookie      == null) twitterCookie = "";
        bilibiliUidCookies = Map.copyOf(bilibiliUidCookies);
        bilibiliUidNames   = Map.copyOf(bilibiliUidNames);
    }

    public static CredentialConfig defaults() {
        return new CredentialConfig("", Map.of(), Map.of(), "");
    }
}
