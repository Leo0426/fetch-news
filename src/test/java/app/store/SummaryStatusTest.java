package app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class SummaryStatusTest {
    @Test void outputLimitHasAnActionableMessageWithoutExposingProviderDetails() {
        var status = error("AI output token limit reached");
        assertEquals("模型输出额度已用尽，请在 AI 设置中提高最大输出 Token 或切换模型后重试。", status.errorMessage());
        assertEquals("原文网站返回 HTTP 403，无法读取正文。请确认文章是否仍可访问。", error("upstream returned HTTP 403").errorMessage());
        assertFalse(error("upstream request failed: private URL").errorMessage().contains("private"));
        assertFalse(error("private upstream credentials").errorMessage().contains("private"));
        assertEquals("上次摘要任务已中断，请重试。", error("Summary interrupted").errorMessage());
    }

    private SummaryStatus error(String detail) {
        return new SummaryStatus("https://example.com/article", "error", null, null, null, null, detail);
    }
}
