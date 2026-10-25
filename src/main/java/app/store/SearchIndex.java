package app.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The FTS5 search index over titles, descriptions and summaries, and the queries that read it.
 */
public final class SearchIndex {
    private final FeedStore store;

    SearchIndex(FeedStore store) {
        this.store = store;
    }

    private static final int SEARCH_INDEX_VERSION = 1;

    /**
     * Full-text search across item titles, descriptions, and AI summaries.
     */
    public List<StoredItem> search(String query, int limit) throws SQLException {
        synchronized (store) {
            return searchHits(query, limit).stream().map(SearchHit::item).toList();
        }
    }

    public List<SearchHit> searchHits(String query, int limit) throws SQLException {
        synchronized (store) {
            if (query == null || query.isBlank()) return List.of();
            if (limit < 1) return List.of();
            if (query.codePointCount(0, query.length()) > 200) {
                throw new IllegalArgumentException("Search queries must not exceed 200 characters");
            }
            List<String> terms = Arrays.stream(query.strip().split("(?U)\\s+"))
                    .filter(term -> !term.isBlank()).distinct().toList();
            if (terms.isEmpty()) return List.of();
            List<String> literals = terms.stream().filter(SearchIndex::isLiteralSearchTerm).toList();
            List<String> words = terms.stream().filter(term -> !isLiteralSearchTerm(term)).toList();
            List<String> parameters = new ArrayList<>();
            StringBuilder sql = new StringBuilder();
            if (!words.isEmpty()) {
                sql.append("""
                    WITH word_matches AS (
                        SELECT rowid, bm25(feed_search_fts, 0, 8, 1, 3) AS rank
                        FROM feed_search_fts
                        WHERE feed_search_fts MATCH ?
                    )
                    """);
                parameters.add(toFtsQuery(String.join(" ", words)));
            }
            sql.append("""
                    SELECT %s, fi.description, s.summary,
                           (0
                    """.formatted(FeedStore.itemColumns("fi.")));
            for (String term : literals) {
                sql.append(" + (instr(lower(coalesce(fi.title, '')), lower(?)) > 0)");
                parameters.add(term);
            }
            sql.append(") AS title_matches FROM feed_items fi ");
            if (!words.isEmpty()) sql.append("JOIN word_matches m ON m.rowid = fi.id ");
            sql.append("""
                    LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                    WHERE trim(fi.title, char(9, 10, 11, 12, 13, 32)) <> ''
                      AND trim(fi.item_link, char(9, 10, 11, 12, 13, 32)) <> ''
                    """);
            for (String term : literals) {
                sql.append("""
                        AND (instr(lower(coalesce(fi.title, '')), lower(?)) > 0
                          OR instr(lower(coalesce(fi.description, '')), lower(?)) > 0
                          OR instr(lower(coalesce(s.summary, '')), lower(?)) > 0)
                        """);
                parameters.addAll(List.of(term, term, term));
            }
            sql.append("ORDER BY title_matches DESC, ");
            if (!words.isEmpty()) sql.append("m.rank, ");
            sql.append("fi.id DESC LIMIT ?");
            List<SearchHit> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement(sql.toString())) {
                int index = 1;
                for (String parameter : parameters) ps.setString(index++, parameter);
                ps.setInt(index, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new SearchHit(FeedStore.readStoredItem(rs), rs.getString("description"), rs.getString("summary")));
                    }
                }
            }
            return result;
        }
    }

    private static boolean isLiteralSearchTerm(String term) {
        return term.codePoints().anyMatch(character ->
                Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN
                        || !Character.isLetterOrDigit(character));
    }

    void initializeSearchIndex(boolean migratedItems) throws SQLException {
        try (Statement statement = store.conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS search_index_state (
                        id INTEGER PRIMARY KEY CHECK(id = 1),
                        version INTEGER NOT NULL
                    )
                    """);
        }
        boolean indexExists;
        try (Statement statement = store.conn.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'feed_search_fts'
                     """)) {
            indexExists = result.next();
        }
        try (Statement statement = store.conn.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM search_index_state WHERE id = 1")) {
            if (!migratedItems && indexExists && result.next() && result.getInt(1) == SEARCH_INDEX_VERSION) {
                return;
            }
        }
        try (Statement statement = store.conn.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS feed_search_fts");
            statement.execute("""
                    CREATE VIRTUAL TABLE feed_search_fts
                    USING fts5(item_link UNINDEXED, title, description, summary)
                    """);
        }
        rebuildSearchIndex();
        try (PreparedStatement statement = store.conn.prepareStatement("""
                INSERT INTO search_index_state (id, version) VALUES (1, ?)
                ON CONFLICT(id) DO UPDATE SET version = excluded.version
                """)) {
            statement.setInt(1, SEARCH_INDEX_VERSION);
            statement.executeUpdate();
        }
    }

    void rebuildSearchIndex() throws SQLException {
        try (Statement s = store.conn.createStatement()) {
            s.execute("DELETE FROM feed_search_fts");
            s.execute("""
                    INSERT INTO feed_search_fts(rowid, item_link, title, description, summary)
                    SELECT fi.id, fi.item_link, fi.title, fi.description, s.summary
                    FROM feed_items fi
                    LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                    """);
        }
    }

    void refreshSearchIndex(List<String> links) throws SQLException {
        List<String> valid = links.stream()
                .filter(link -> link != null && !link.isBlank())
                .distinct()
                .toList();
        if (valid.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(valid.size(), "?"));
        try (PreparedStatement delete = store.conn.prepareStatement(
                     "DELETE FROM feed_search_fts WHERE item_link IN (" + placeholders + ")");
             PreparedStatement insert = store.conn.prepareStatement("""
                     INSERT INTO feed_search_fts(rowid, item_link, title, description, summary)
                     SELECT fi.id, fi.item_link, fi.title, fi.description, s.summary
                     FROM feed_items fi
                     LEFT JOIN ai_summaries s ON s.item_link = fi.item_link
                     WHERE fi.item_link IN (%s)
                     """.formatted(placeholders))) {
            for (int i = 0; i < valid.size(); i++) {
                delete.setString(i + 1, valid.get(i));
                insert.setString(i + 1, valid.get(i));
            }
            delete.executeUpdate();
            insert.executeUpdate();
        }
    }

    private static String toFtsQuery(String query) {
        return Arrays.stream(query.strip().split("\\s+"))
                .filter(term -> !term.isBlank())
                .map(term -> "\"" + term.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(" "));
    }
}
