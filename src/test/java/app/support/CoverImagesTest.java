package app.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CoverImagesTest {
    private static final String BASE = "https://example.com/posts/story";

    @Test
    void recognizesLazyResponsivePictureAndNoscriptImages() {
        for (String html : new String[]{
                "<IMG data-original='../cover.jpg' src='data:image/gif;base64,AAAA'>",
                "<img data-lazy-src='../cover.jpg'>",
                "<![CDATA[<p>Archived body</p><img src='../cover.jpg'>]]>",
                "<img data-src=' ' src='../cover.jpg'>",
                "<img data-src='javascript:bad()' src='../cover.jpg'>",
                "<img srcset='../small.jpg 320w, ../cover.jpg 1200w'>",
                "<img data-srcset='../small.jpg 1x, ../cover.jpg 2x' src='/placeholder.gif'>",
                "<picture><source srcset='../cover.jpg 2x'><img src='data:image/gif;base64,AAAA'></picture>",
                "<noscript><img src='../cover.jpg'></noscript>"}) {
            assertEquals("https://example.com/cover.jpg", CoverImages.firstInHtml(html, BASE), html);
        }
    }

    @Test
    void skipsPixelsAndUnsafeImagesWithoutDiscardingLaterPhotos() {
        assertEquals("https://example.com/cover.jpg", CoverImages.firstInHtml("""
                <img src='/pixel.gif' width='1px' height='1'>
                <img src='javascript:bad()'><img src='https://user:secret@example.com/private.jpg'>
                <img src='https:/broken.jpg'><img data-src='../cover.jpg'>
                """, BASE));
        assertNull(CoverImages.firstInHtml("<img src='file:///etc/passwd'><img src='data:image/png;base64,AA'>", BASE));
    }
}
