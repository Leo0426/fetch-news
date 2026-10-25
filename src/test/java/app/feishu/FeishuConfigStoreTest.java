package app.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeishuConfigStoreTest {
    @TempDir Path directory;

    @Test
    void movesRulesFromRenamedCategoriesWithoutOverwritingExistingOnes() throws Exception {
        Path file = directory.resolve("feishu.json");
        Files.writeString(file, """
                {"webhookUrl":"https://example.com/hook","enabled":true,"maxItemsPerPush":10,
                 "categories":{
                   "中文媒体":{"enabled":true,"mode":"immediate","maxItemsPerPush":0},
                   "科技资讯":{"enabled":false,"mode":"digest","digestCron":"0 0 8 * * *","maxItemsPerPush":5}}}
                """);
        var renamed = Map.of("中文媒体", List.of("时事要闻", "科技资讯"));

        var config = new FeishuConfigStore(file, new ObjectMapper(), renamed).get();

        assertFalse(config.categories().containsKey("中文媒体"));
        assertTrue(config.categories().get("时事要闻").enabled());
        assertEquals(FeishuCategoryConfig.MODE_IMMEDIATE, config.categories().get("时事要闻").mode());
        assertFalse(config.categories().get("科技资讯").enabled(), "an existing rule wins over a migrated one");
        assertTrue(new FeishuConfigStore(file, new ObjectMapper()).get().categories().containsKey("时事要闻"),
                "the migration is saved so it runs once");
    }
}
