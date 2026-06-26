package app.feishu;

import java.util.Map;

/**
 * Global Feishu push configuration.
 *
 * @param webhookUrl      default webhook URL used when a category has no specific one
 * @param enabled         global on/off switch
 * @param maxItemsPerPush default cap per message; per-category value overrides when > 0
 * @param categories      per-category configurations keyed by category name
 */
public record FeishuConfig(
        String webhookUrl,
        boolean enabled,
        int maxItemsPerPush,
        Map<String, FeishuCategoryConfig> categories) {

    public FeishuConfig {
        if (webhookUrl == null) webhookUrl = "";
        if (maxItemsPerPush < 1) maxItemsPerPush = 10;
        if (categories == null) categories = Map.of();
        categories = Map.copyOf(categories);
    }

    public static FeishuConfig defaults() {
        return new FeishuConfig("", false, 10, Map.of());
    }

    public FeishuCategoryConfig getCategory(String category) {
        return categories.getOrDefault(category, FeishuCategoryConfig.defaults());
    }

    /** Effective webhook for a category: category-specific URL if set, otherwise global. */
    public String effectiveWebhook(String category) {
        String catUrl = categories.containsKey(category) ? categories.get(category).webhookUrl() : "";
        return (catUrl != null && !catUrl.isBlank()) ? catUrl : webhookUrl;
    }

    /** Effective max-items for a category: category-specific value if > 0, otherwise global. */
    public int effectiveMaxItems(String category) {
        int catMax = categories.containsKey(category) ? categories.get(category).maxItemsPerPush() : 0;
        return catMax > 0 ? catMax : maxItemsPerPush;
    }
}
