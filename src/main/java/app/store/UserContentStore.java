package app.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Account-scoped records for the administrator's reading history view. */
public final class UserContentStore {
    public enum Section {
        BOOKMARKS("bookmarks", "收藏", "bookmarks", "bookmarked_at"),
        READ("read", "已读记录", "read_items", "read_at"),
        FEEDBACK("feedback", "精选反馈", "pick_feedback", "created_at");

        private final String id;
        private final String label;
        private final String table;
        private final String timestamp;

        Section(String id, String label, String table, String timestamp) {
            this.id = id;
            this.label = label;
            this.table = table;
            this.timestamp = timestamp;
        }

        public String id() { return id; }
        public String label() { return label; }

        public static Optional<Section> fromId(String id) {
            for (Section section : values()) {
                if (section.id.equals(id)) return Optional.of(section);
            }
            return Optional.empty();
        }
    }

    public record Row(String link, String title, boolean stored, String time, String kind, String verdict) {}
    public record Page(List<Row> rows, long total, long number, long pages) {}

    private static final int PAGE_SIZE = 20;
    private final FeedStore store;

    UserContentStore(FeedStore store) {
        this.store = store;
    }

    public Page page(long id, Section section, long requestedPage) throws SQLException {
        return store.read(conn -> {
            long total = count(conn, id, section);
            long pages = Math.max(1, Math.ceilDiv(total, PAGE_SIZE));
            long number = Math.clamp(requestedPage, 1, pages);
            String title = section == Section.BOOKMARKS
                    ? "coalesce(nullif(i.title, ''), nullif(s.title, ''), s.item_link)"
                    : "coalesce(nullif(i.title, ''), s.item_link)";
            String feedback = section == Section.FEEDBACK ? "s.kind, s.verdict" : "NULL AS kind, NULL AS verdict";
            String sql = """
                    SELECT s.item_link, %s AS title, i.item_link IS NOT NULL AS stored,
                           replace(substr(s.%s, 1, 16), 'T', ' ') AS time, %s
                    FROM %s s LEFT JOIN feed_items i ON i.item_link = s.item_link
                    WHERE s.user_id = ?
                    ORDER BY s.%s DESC, s.item_link, kind LIMIT ? OFFSET ?
                    """.formatted(title, section.timestamp, feedback, section.table, section.timestamp);
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setInt(2, PAGE_SIZE);
                ps.setLong(3, (number - 1) * PAGE_SIZE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getString("item_link"), rs.getString("title"), rs.getBoolean("stored"),
                                rs.getString("time"), rs.getString("kind"), rs.getString("verdict")));
                    }
                }
            }
            return new Page(List.copyOf(rows), total, number, pages);
        });
    }

    public void change(long userId, Section section, String action, String link, String kind) throws SQLException {
        synchronized (store) {
            store.inTransaction(() -> {
                if (store.accounts().findUser(userId) == null) throw new NoSuchElementException("账户不存在。");
                if (section == Section.FEEDBACK) {
                    changeFeedback(userId, link, kind, action);
                    return null;
                }
                switch (action) {
                    case "add" -> add(userId, section, link.strip());
                    case "remove" -> {
                        if (section == Section.BOOKMARKS) store.reading().removeBookmark(userId, link);
                        else store.reading().unmarkRead(userId, link);
                    }
                    default -> throw new IllegalArgumentException("请选择添加或移除记录。");
                }
                return null;
            });
        }
    }

    private void changeFeedback(long id, String link, String kind, String action) throws SQLException {
        PickKind label;
        try {
            label = PickKind.of(kind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("请选择必知或精选标签。");
        }
        if ("remove".equals(action)) {
            store.picks().setFeedback(id, link, label, null, Instant.now());
            return;
        }
        if (!PickFeedback.VERDICTS.contains(action)) throw new IllegalArgumentException("请选择准、不准或撤销反馈。");
        // A correction from a stale page must never re-create a withdrawn verdict.
        try (PreparedStatement ps = store.conn.prepareStatement("""
                UPDATE pick_feedback SET verdict = ?, created_at = ?
                WHERE user_id = ? AND item_link = ? AND kind = ?
                """)) {
            ps.setString(1, action);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, id);
            ps.setString(4, link);
            ps.setString(5, label.id());
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("这条反馈已不存在。");
        }
    }

    private void add(long id, Section section, String link) throws SQLException {
        if (link.isEmpty()) throw new IllegalArgumentException("请输入文章原文链接。");
        String sql = "SELECT title, route_path FROM feed_items WHERE item_link = ?";
        try (PreparedStatement ps = store.conn.prepareStatement(sql)) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("未找到这篇文章，请输入已收录文章的原文链接。");
                if (section == Section.BOOKMARKS) {
                    store.reading().saveBookmark(id, link, rs.getString("title"), rs.getString("route_path"));
                } else {
                    store.reading().markRead(id, link);
                }
            }
        }
    }

    private static long count(Connection conn, long id, Section section) throws SQLException {
        String sql = "SELECT count(*) FROM " + section.table + " WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
