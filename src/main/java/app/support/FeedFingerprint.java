package app.support;

import app.core.Feed;
import app.core.FeedItem;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FeedFingerprint {
    private FeedFingerprint() {}

    public static String of(Feed feed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, feed.title());
            append(digest, feed.link());
            append(digest, feed.description());
            appendLength(digest, feed.items().size());
            for (FeedItem item : feed.items()) {
                append(digest, item.title());
                append(digest, item.link());
                append(digest, item.description());
                append(digest, item.pubDate() == null ? null : item.pubDate().toString());
                append(digest, item.author());
                appendLength(digest, item.categories().size());
                for (String category : item.categories()) append(digest, category);
            }
            return "v1:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
        }
    }

    private static void append(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        // Length framing distinguishes absent values and embedded delimiters in source text.
        appendLength(digest, bytes == null ? -1 : bytes.length);
        if (bytes != null) digest.update(bytes);
    }

    private static void appendLength(MessageDigest digest, int length) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }
}
