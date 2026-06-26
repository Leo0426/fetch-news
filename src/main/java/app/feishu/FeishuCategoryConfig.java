package app.feishu;

/**
 * Per-category Feishu push configuration.
 *
 * @param enabled         whether push is active for this category
 * @param webhookUrl      category-specific webhook; empty = use global
 * @param mode            {@link #MODE_IMMEDIATE} or {@link #MODE_DIGEST}
 * @param digestCron      Spring 6-field cron for digest mode (e.g. {@code "0 0 8 * * *"})
 * @param maxItemsPerPush items per push; 0 = use global default
 */
public record FeishuCategoryConfig(
        boolean enabled,
        String webhookUrl,
        String mode,
        String digestCron,
        int maxItemsPerPush) {

    public static final String MODE_IMMEDIATE = "immediate";
    public static final String MODE_DIGEST    = "digest";

    public FeishuCategoryConfig {
        if (webhookUrl  == null) webhookUrl  = "";
        if (digestCron  == null) digestCron  = "";
        if (!MODE_DIGEST.equals(mode))  mode = MODE_IMMEDIATE;
        if (maxItemsPerPush < 0) maxItemsPerPush = 0;
    }

    public boolean isImmediate() { return MODE_IMMEDIATE.equals(mode); }
    public boolean isDigest()    { return MODE_DIGEST.equals(mode); }

    public static FeishuCategoryConfig defaults() {
        return new FeishuCategoryConfig(false, "", MODE_IMMEDIATE, "", 0);
    }
}
