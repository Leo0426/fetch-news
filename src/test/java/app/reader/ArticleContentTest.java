package app.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class ArticleContentTest {
    @Test
    void prefersSavedSourceAndPreservesReadingStructure() {
        ArticleContent content = ArticleContent.from(
                "<h2>本地阅读</h2><p>保留<strong>文章</strong>结构。</p><p>第二段。</p>",
                "AI 改写内容", "https://example.com/posts/article");

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("source", content.kind());
        assertFalse(content.isEmpty());
        assertEquals("本地阅读", body.selectFirst("h2").text());
        assertEquals(2, body.select("p").size());
        assertEquals("文章", body.selectFirst("strong").text());
        assertFalse(body.text().contains("AI 改写内容"));
    }

    @Test
    void discardsActiveSubtreesAttributesAndTrackingPixels() {
        ArticleContent content = ArticleContent.from("""
                <p id="victim" style="background:url(https://tracking.test/bg)" onclick="alert(1)">保留正文。</p>
                <script>alert('script')</script><style>p {display:none}</style>
                <iframe src="https://tracking.test/frame">框架文本</iframe>
                <form action="https://tracking.test/post"><p>表单文本</p><input value="private"></form>
                <svg onload="alert(1)"><text>矢量文本</text></svg>
                <object data="https://tracking.test/object">对象文本</object>
                <video src="https://tracking.test/video">视频文本</video>
                <img src="https://tracking.test/pixel" srcset="https://tracking.test/pixel2 2x" width="1" alt="图片文字">
                <link rel="stylesheet" href="https://tracking.test/style"><meta http-equiv="refresh" content="0">
                """, null, "https://example.com/article");

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("保留正文。", body.text());
        assertTrue(body.select("script, style, iframe, form, svg, object, video, img, link, meta").isEmpty());
        assertEquals(0, body.selectFirst("p").attributes().size());
        assertFalse(content.html().contains("tracking.test"));
    }

    @Test
    void displaysSafeBodyImagesAndCaptionsWithoutPublisherScriptsOrStyles() {
        ArticleContent content = ArticleContent.from("""
                <figure style="width:9999px"><picture><source srcset="/photo.jpg 1200w">
                  <img src="data:image/gif;base64,AA" alt="正文配图" onerror="bad()"></picture>
                  <figcaption>图片说明</figcaption></figure>
                <noscript><img data-original="/second.jpg"></noscript>
                <img src="javascript:bad()"><img src="https://user:secret@example.com/private">
                """, null, "https://example.com/article");
        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("source", content.kind());
        assertEquals(2, body.select("img").size());
        assertEquals("https://example.com/photo.jpg", body.selectFirst("img").attr("src"));
        assertEquals("正文配图", body.selectFirst("img").attr("alt"));
        assertEquals("图片说明", body.selectFirst("figcaption").text());
        assertTrue(body.select("source, [onerror], [style], [srcset]").isEmpty());
        body.select("img").forEach(image -> {
            assertEquals("lazy", image.attr("loading"));
            assertEquals("no-referrer", image.attr("referrerpolicy"));
        });
    }

    @Test
    void imageOnlyStoriesAreReadableAndExtractFallbackRetainsSourcePhotos() {
        String image = "<img src='/photo.jpg'>";
        ArticleContent photo = ArticleContent.from(image, null, "https://example.com/article");
        assertFalse(photo.isEmpty());
        assertEquals(1, Jsoup.parse(photo.html()).select("img").size());
        ArticleContent extracted = ArticleContent.from("<p>Preview</p>" + image,
                "Full article. ".repeat(30), "https://example.com/article");
        assertEquals("extract", extracted.kind());
        assertEquals(1, Jsoup.parse(extracted.html()).select("img").size());
        assertTrue(Jsoup.parse(extracted.html()).text().contains("Full article."));
    }

    @Test
    void noscriptFallbackDoesNotDuplicateTheSameLazyPhoto() {
        ArticleContent content = ArticleContent.from("""
                <img data-src="/photo.jpg" src="data:image/gif;base64,AA">
                <noscript><img src="/photo.jpg"></noscript>
                """, null, "https://example.com/article");
        assertEquals(1, Jsoup.parse(content.html()).select("img").size());
    }

    @Test
    void restoresImagesAndStructureFromLegacyCdataBodies() {
        ArticleContent content = ArticleContent.from(
                "<![CDATA[<p>Archived body</p><img src='/archive.jpg'><script>bad()</script>]]>",
                null, "https://example.com/article");
        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("Archived body", body.text());
        assertEquals("https://example.com/archive.jpg", body.selectFirst("img").attr("src"));
        assertTrue(body.select("script").isEmpty());
    }

    @Test
    void dropsArchivedEscapedReadMoreFooterAndPreservesThePrecedingPlainParagraphs() {
        String url = "https://sspai.com/post/114741";
        String footer = "&lt;a href=\"" + url + "\" target=\"_blank\"&gt;查看全文&lt;/a&gt;";
        var content = ArticleContent.from("第一段。\n\n文章代表作者个人观点 ..." + footer, null, url);
        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("source", content.kind());
        assertEquals(2, body.select("p").size());
        assertEquals("第一段。 文章代表作者个人观点 ...", body.text());
        assertTrue(body.select("a").isEmpty());
    }

    @Test
    void keepsEscapedAnchorExamplesAndOnlyRecognizesTheArticleOwnTerminalFooter() {
        String url = "https://sspai.com/post/114741";
        String sample = "&lt;a href=\"https://example.test/example\"&gt;查看全文&lt;/a&gt;";
        String footer = "&lt;a href=\"" + url + "\"&gt;查看全文&lt;/a&gt;";
        var plain = ArticleContent.from("普通文字：&lt;b&gt;示例&lt;/b&gt; " + sample, null, url);
        assertEquals("普通文字：<b>示例</b> <a href=\"https://example.test/example\">查看全文</a>",
                Jsoup.parse(plain.html()).text());
        var inline = ArticleContent.from("示例：" + footer + "。下面继续。", null, url);
        assertTrue(Jsoup.parse(inline.html()).text().contains("<a href=\"" + url + "\">查看全文</a>"));
        var html = ArticleContent.from("<p>链接示例：</p><pre><code>" + footer + "</code></pre>" + footer,
                null, url);
        Document body = Jsoup.parseBodyFragment(html.html());
        assertEquals("<a href=\"" + url + "\">查看全文</a>", body.selectFirst("code").text());
        assertEquals("链接示例： <a href=\"" + url + "\">查看全文</a>", body.text());
    }

    @Test
    void resolvesHttpLinksAndRemovesUnsafeDestinations() {
        ArticleContent content = ArticleContent.from("""
                <p><a href="../guide?q=模型&amp;x=1#details" title="指南" onclick="alert(1)">相对地址</a>
                <a href="https://other.test/story" ping="https://tracking.test">绝对地址</a>
                <a href="//cdn.test/story">同协议地址</a>
                <a href="javascript:alert(1)">脚本</a><a href="data:text/html,bad">内联数据</a>
                <a href="file:///etc/passwd">本地文件</a><a href="mailto:test@example.com">邮箱</a>
                <a href="https://">缺少主机</a><a href="java&#x09;script:alert(1)">混淆脚本</a></p>
                """, null, "https://example.com/posts/article");

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals(3, body.select("a[href]").size());
        assertEquals("https://example.com/guide?q=模型&x=1#details", body.select("a").get(0).attr("href"));
        assertEquals("https://other.test/story", body.select("a").get(1).attr("href"));
        assertEquals("https://cdn.test/story", body.select("a").get(2).attr("href"));
        body.select("a[href]").forEach(link -> {
            assertEquals("_blank", link.attr("target"));
            assertEquals("noopener noreferrer", link.attr("rel"));
            assertEquals(3, link.attributes().size());
        });
        assertFalse(content.html().contains("javascript:"));
        assertFalse(content.html().contains("tracking.test"));
    }

    @Test
    void keepsPlainSourceParagraphsAndLineBreaks() {
        ArticleContent content = ArticleContent.from("第一行\r\n第二行\r\n\r\n下一段：2 < 3 & 4 > 1。",
                null, "https://example.com/article");

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("source", content.kind());
        assertEquals(2, body.select("p").size());
        assertEquals(1, body.select("br").size());
        assertEquals("第一行 第二行", body.select("p").first().text());
        assertEquals("下一段：2 < 3 & 4 > 1。", body.select("p").last().text());
    }

    @Test
    void keepsAngleBracketProseAndItsParagraphsAsPlainText() {
        ArticleContent content = ArticleContent.from(
                "第一段：这是纯文本。\n第二行\n\n第二段包含 <角括号文字> 与 2 < 3。\n\n第三段",
                null, "https://example.com/article");

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("source", content.kind());
        assertEquals(3, body.select("p").size());
        assertEquals(1, body.select("br").size());
        assertEquals("第二段包含 <角括号文字> 与 2 < 3。", body.select("p").get(1).text());
        assertTrue(body.select("角括号文字").isEmpty());
    }

    @Test
    void keepsGenericTypeParametersAndPlaceholdersInPlainSource() {
        ArticleContent content = ArticleContent.from(
                "Java 泛型 List<T> 保留参数。\n\nMap<K,V> 表示映射。\n\n命令占位符 <name> 替换用户名。",
                null, "https://example.com/article");

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals(3, body.select("p").size());
        assertEquals("Java 泛型 List<T> 保留参数。", body.select("p").get(0).text());
        assertEquals("Map<K,V> 表示映射。", body.select("p").get(1).text());
        assertEquals("命令占位符 <name> 替换用户名。", body.select("p").get(2).text());
    }

    @Test
    void usesExtractWhenSourceHasNoTextAndEscapesItsMarkup() {
        ArticleContent content = ArticleContent.from(
                "<p>&nbsp;</p><img src='https://tracking.test' alt='图片不是正文'><script>alert(1)</script>",
                "AI 提取内容 <strong>这是纯文本</strong>\n\n<script>alert(1)</script>",
                "https://example.com/article");

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("extract", content.kind());
        assertEquals(2, body.select("p").size());
        assertTrue(body.select("script, strong").isEmpty());
        assertTrue(body.text().contains("<strong>这是纯文本</strong>"));
        assertTrue(body.text().contains("<script>alert(1)</script>"));
    }

    @Test
    void shortCleanedPreviewUsesFullExtractAsEscapedPlainText() {
        String extract = "已保存的完整正文。".repeat(30) + "\n\n<script>alert(1)</script><img src='https://tracking.test'>";
        ArticleContent content = ArticleContent.from("<p>订阅预告</p><script>"
                + "Ignored active content".repeat(30) + "</script>", extract, "https://example.com/article");

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("extract", content.kind());
        assertEquals(2, body.select("p").size());
        assertTrue(body.select("script, img").isEmpty());
        assertTrue(body.text().contains("<script>alert(1)</script>"));
        assertTrue(body.text().contains("<img src='https://tracking.test'>"));
        assertFalse(body.text().contains("订阅预告"));
    }

    @Test
    void completenessThresholdUsesTwoHundredUnicodeCodePoints() {
        String shortSource = "𠀀".repeat(199);
        String fullExtract = "𠀁".repeat(200);
        assertEquals("extract", ArticleContent.from(shortSource, fullExtract, null).kind());
        assertEquals("source", ArticleContent.from("𠀀".repeat(200), fullExtract, null).kind());
        assertEquals("source", ArticleContent.from(shortSource, "𠀁".repeat(199), null).kind());
        assertEquals(shortSource, Jsoup.parseBodyFragment(
                ArticleContent.from(shortSource, "𠀁".repeat(199), null).html()).text());
    }

    @Test
    void completeSourceKeepsItsStructureEvenWhenExtractIsLonger() {
        String original = "完整订阅正文。".repeat(40);
        ArticleContent content = ArticleContent.from("<h2>文章小标题</h2><p><strong>" + original + "</strong></p>",
                "更长的缓存内容。".repeat(80), null);

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("source", content.kind());
        assertEquals("文章小标题", body.selectFirst("h2").text());
        assertEquals(original, body.selectFirst("strong").text());
        assertFalse(body.text().contains("更长的缓存内容"));
    }

    @Test
    void emptyAndUnicodeWhitespaceContentStayEmpty() {
        for (String source : new String[]{null, "", " \t\n\u00a0\u2003\u202f", "<p>&nbsp;\u2003</p>",
                "<!-- no article --><img src='https://tracking.test' width='1' alt='替代文字'>",
                "<form><p>表单内容</p></form><script>bad()</script><svg><text>图形</text></svg>"}) {
            ArticleContent content = ArticleContent.from(source, " \n\u00a0\u202f", null);
            assertTrue(content.isEmpty(), source);
            assertEquals("empty", content.kind());
            assertEquals("", content.html());
        }
    }

    @Test
    void retainsCodeTablesAndLongTextWithoutTruncation() {
        String longText = "正文📚".repeat(5000);
        ArticleContent content = ArticleContent.from("<h1>文章小标题</h1><pre><code>if (x &lt; 3) {\n  run();\n}</code></pre>"
                + "<table><thead><tr><th>名称</th></tr></thead><tbody><tr><td>内容</td></tr></tbody></table>"
                + "<p>" + longText + "</p>", "不应使用 AI 内容", null);

        Document body = Jsoup.parseBodyFragment(content.html());
        assertEquals("source", content.kind());
        assertTrue(body.text().contains("文章小标题"));
        assertTrue(body.select("h1").isEmpty());
        assertEquals("if (x < 3) {\n  run();\n}", body.selectFirst("pre code").wholeText());
        assertEquals("名称", body.selectFirst("table th").text());
        assertEquals("内容", body.selectFirst("table td").text());
        assertEquals(longText, body.selectFirst("p").text());
    }

    @Test
    void invalidBaseDoesNotAuthorizeRelativeLinksOrSuppressSafeAbsoluteLinks() {
        for (String base : new String[]{null, "relative/path", "file:///tmp/article", "not a url", "https://"}) {
            ArticleContent content = ArticleContent.from("<a href='../story'>相对</a>"
                    + "<a href='HTTPS://safe.test/story'>绝对</a>"
                    + "<a href='https://user:secret@safe.test/story'>凭据</a>", null, base);
            Document body = Jsoup.parseBodyFragment(content.html());
            assertEquals(1, body.select("a[href]").size(), base);
            assertEquals("HTTPS://safe.test/story", body.selectFirst("a[href]").attr("href"));
        }
    }
}
