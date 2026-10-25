package app.store;

import app.auth.UserAccount;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Each reader's bookmarks and read state. Every operation names the reader; there is no implicit owner.
 */
public final class ReadingState {
    private final FeedStore store;

    ReadingState(FeedStore store) {
        this.store = store;
    }

    static void createSchema(Statement s) throws SQLException {
        // Single-user installs kept bookmarks and read state without an owner; they belong to the admin.
        migrateToPerUserTable(s, "bookmarks", """
                CREATE TABLE bookmarks (
                    user_id       INTEGER NOT NULL,
                    item_link     TEXT NOT NULL,
                    title         TEXT,
                    route_path    TEXT,
                    bookmarked_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, item_link)
                )""", "item_link, title, route_path, bookmarked_at");
        migrateToPerUserTable(s, "read_items", """
                CREATE TABLE read_items (
                    user_id   INTEGER NOT NULL,
                    item_link TEXT NOT NULL,
                    read_at   TEXT NOT NULL,
                    PRIMARY KEY (user_id, item_link)
                )""", "item_link, read_at");
    }

    private static void migrateToPerUserTable(Statement s, String table, String createSql, String columns)
            throws SQLException {
        boolean exists = false;
        boolean hasUserId = false;
        try (ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                exists = true;
                if ("user_id".equals(rs.getString("name"))) hasUserId = true;
            }
        }
        if (hasUserId) return;
        if (!exists) {
            s.execute(createSql);
            return;
        }
        s.execute("ALTER TABLE " + table + " RENAME TO " + table + "_single_user");
        s.execute(createSql);
        s.execute("INSERT INTO " + table + " (user_id, " + columns + ") SELECT " + UserAccount.OWNER_ID + ", "
                + columns + " FROM " + table + "_single_user");
        s.execute("DROP TABLE " + table + "_single_user");
    }

    public void saveBookmark(long userId, String link, String title, String routePath)
            throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO bookmarks (user_id, item_link, title, route_path, bookmarked_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(user_id, item_link) DO UPDATE
                        SET title = excluded.title, route_path = excluded.route_path
                    """)) {
                ps.setLong(1, userId);
                ps.setString(2, link);
                ps.setString(3, title);
                ps.setString(4, routePath);
                ps.setString(5, Instant.now().toString());
                ps.executeUpdate();
            }
        }
    }

    public void removeBookmark(long userId, String link) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "DELETE FROM bookmarks WHERE user_id = ? AND item_link = ?")) {
                ps.setLong(1, userId);
                ps.setString(2, link);
                ps.executeUpdate();
            }
        }
    }

    public boolean isBookmarked(long userId, String link) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT 1 FROM bookmarks WHERE user_id = ? AND item_link = ?")) {
                ps.setLong(1, userId);
                ps.setString(2, link);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }

    /** Returns all bookmarked item links for initialising client-side state. */
    public List<String> loadBookmarkLinks(long userId) throws SQLException {
        synchronized (store) {
            List<String> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT item_link FROM bookmarks WHERE user_id = ? ORDER BY bookmarked_at DESC")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(rs.getString("item_link"));
                }
            }
            return result;
        }
    }

    /** Returns full bookmark records newest first. */
    public List<BookmarkedItem> loadBookmarks(long userId) throws SQLException {
        synchronized (store) {
            List<BookmarkedItem> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    SELECT item_link, title, route_path, bookmarked_at
                    FROM bookmarks WHERE user_id = ? ORDER BY bookmarked_at DESC
                    """)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String ts = rs.getString("bookmarked_at");
                        result.add(new BookmarkedItem(
                                rs.getString("item_link"),
                                rs.getString("title"),
                                rs.getString("route_path"),
                                ts != null ? Instant.parse(ts) : null));
                    }
                }
            }
            return result;
        }
    }


    /** Marks an item as read; does nothing if already marked. */
    public void markRead(long userId, String link) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "INSERT OR IGNORE INTO read_items (user_id, item_link, read_at) VALUES (?, ?, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, link);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
        }
    }

    /** Removes the read mark for an item. */
    public void unmarkRead(long userId, String link) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "DELETE FROM read_items WHERE user_id = ? AND item_link = ?")) {
                ps.setLong(1, userId);
                ps.setString(2, link);
                ps.executeUpdate();
            }
        }
    }

    /** Returns true if the item has been marked as read. */
    public boolean isRead(long userId, String link) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT 1 FROM read_items WHERE user_id = ? AND item_link = ?")) {
                ps.setLong(1, userId);
                ps.setString(2, link);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }

    /** Returns all read item links, newest first. */
    public List<String> loadReadLinks(long userId) throws SQLException {
        synchronized (store) {
            List<String> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT item_link FROM read_items WHERE user_id = ? ORDER BY read_at DESC")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(rs.getString("item_link"));
                }
            }
            return result;
        }
    }

    /** Marks all supplied links as read atomically. */
    public void markAllRead(long userId, List<String> links) throws SQLException {
        synchronized (store) {
            if (links.isEmpty()) return;
            String now = Instant.now().toString();
            String values = String.join(",", Collections.nCopies(links.size(), "(?, ?, ?)"));
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "INSERT OR IGNORE INTO read_items (user_id, item_link, read_at) VALUES " + values)) {
                int index = 1;
                for (String link : links) {
                    ps.setLong(index++, userId);
                    ps.setString(index++, link);
                    ps.setString(index++, now);
                }
                ps.executeUpdate();
            }
        }
    }

    /** Forgets everything one reader bookmarked or read; used when the account is deleted. */
    void deleteAll(long userId) throws SQLException {
        synchronized (store) {
            for (String sql : List.of("DELETE FROM bookmarks WHERE user_id = ?", "DELETE FROM read_items WHERE user_id = ?")) {
                try (PreparedStatement ps = store.conn.prepareStatement(sql)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            }
        }
    }
}
