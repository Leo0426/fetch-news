package app.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Triage labels ({@code item_picks}) and readers' 准/不准 verdicts on them ({@code pick_feedback}).
 */
public final class PickStore {
    private final FeedStore store;

    PickStore(FeedStore store) {
        this.store = store;
    }

    static void createSchema(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS item_picks (
                    item_link        TEXT PRIMARY KEY,
                    category         TEXT NOT NULL,
                    evaluated_at     TEXT NOT NULL,
                    model            TEXT,
                    must_know_reason TEXT,
                    quality_reason   TEXT
                )""");
        s.execute("""
                CREATE TABLE IF NOT EXISTS pick_feedback (
                    user_id    INTEGER NOT NULL,
                    item_link  TEXT NOT NULL,
                    kind       TEXT NOT NULL,
                    verdict    TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, item_link, kind)
                )""");
    }

    /**
     * Returns which of {@code links} were evaluated as part of {@code category}; an article triaged
     * under a category that has since been renamed or regrouped counts as unseen.
     */
    public Set<String> triagedLinks(List<String> links, String category) throws SQLException {
        synchronized (store) {
            Set<String> result = new HashSet<>();
            if (links.isEmpty()) return result;
            String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT item_link FROM item_picks WHERE category = ? AND item_link IN (" + placeholders + ")")) {
                ps.setString(1, category);
                for (int i = 0; i < links.size(); i++) ps.setString(i + 2, links.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(rs.getString("item_link"));
                }
            }
            return result;
        }
    }

    /** Returns picks for the given links; links evaluated without a pick are omitted. */
    public Map<String, ItemPick> labels(List<String> links) throws SQLException {
        synchronized (store) {
            Map<String, ItemPick> result = new HashMap<>();
            if (links.isEmpty()) return result;
            String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    SELECT item_link, must_know_reason, quality_reason FROM item_picks
                    WHERE (must_know_reason IS NOT NULL OR quality_reason IS NOT NULL) AND item_link IN (%s)
                    """.formatted(placeholders))) {
                for (int i = 0; i < links.size(); i++) ps.setString(i + 1, links.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("item_link"),
                                new ItemPick(rs.getString("must_know_reason"), rs.getString("quality_reason")));
                    }
                }
            }
            return result;
        }
    }

    /**
     * Records one triage pass over a category: every evaluated link is stored, and its previous
     * pick is replaced, so re-ranking a window can move a label to a more important article.
     */
    public void saveTriage(String category, List<String> evaluated, Map<String, ItemPick> picks,
                                        String model, Instant evaluatedAt) throws SQLException {
        synchronized (store) {
            store.inTransaction(() -> {
                try (PreparedStatement ps = store.conn.prepareStatement("""
                        INSERT INTO item_picks(item_link, category, evaluated_at, model, must_know_reason, quality_reason)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(item_link) DO UPDATE SET
                            category = excluded.category, evaluated_at = excluded.evaluated_at, model = excluded.model,
                            must_know_reason = excluded.must_know_reason, quality_reason = excluded.quality_reason
                        """)) {
                    for (String link : evaluated) {
                        ItemPick pick = picks.get(link);
                        ps.setString(1, link);
                        ps.setString(2, category);
                        ps.setString(3, evaluatedAt.toString());
                        ps.setString(4, model);
                        ps.setString(5, pick == null ? null : pick.mustKnowReason());
                        ps.setString(6, pick == null ? null : pick.qualityReason());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
        }
    }

    /**
     * Records whether a reader agrees ({@code up}) or disagrees ({@code down}) with a triage label;
     * {@code null} clears the verdict.
     */
    public void setFeedback(long userId, String link, PickKind kind, String verdict, Instant at)
            throws SQLException {
        synchronized (store) {
            if (verdict == null) {
                try (PreparedStatement ps = store.conn.prepareStatement(
                        "DELETE FROM pick_feedback WHERE user_id = ? AND item_link = ? AND kind = ?")) {
                    ps.setLong(1, userId);
                    ps.setString(2, link);
                    ps.setString(3, kind.id());
                    ps.executeUpdate();
                }
                return;
            }
            if (!PickFeedback.VERDICTS.contains(verdict)) throw new IllegalArgumentException("Unknown verdict: " + verdict);
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    INSERT INTO pick_feedback(user_id, item_link, kind, verdict, created_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(user_id, item_link, kind) DO UPDATE SET verdict = excluded.verdict, created_at = excluded.created_at
                    """)) {
                ps.setLong(1, userId);
                ps.setString(2, link);
                ps.setString(3, kind.id());
                ps.setString(4, verdict);
                ps.setString(5, at.toString());
                ps.executeUpdate();
            }
        }
    }

    /** Returns one reader's verdicts on the given links, keyed by link. */
    public Map<String, PickFeedback> feedback(long userId, List<String> links)
            throws SQLException {
        synchronized (store) {
            Map<String, PickFeedback> result = new HashMap<>();
            if (links.isEmpty()) return result;
            String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
            try (PreparedStatement ps = store.conn.prepareStatement(
                    "SELECT item_link, kind, verdict FROM pick_feedback WHERE user_id = ? AND item_link IN (" + placeholders + ")")) {
                ps.setLong(1, userId);
                for (int i = 0; i < links.size(); i++) ps.setString(i + 2, links.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PickFeedback current = result.getOrDefault(rs.getString("item_link"), PickFeedback.NONE);
                        result.put(rs.getString("item_link"),
                                current.with(PickKind.of(rs.getString("kind")), rs.getString("verdict")));
                    }
                }
            }
            return result;
        }
    }

    /**
     * Returns recent reader verdicts on a category's labels, newest first, at most {@code perBucket}
     * per kind and verdict. Triage shows them to the model as examples of the readers' taste.
     */
    public List<PickFeedback.Example> feedbackExamples(String category, Instant since,
                                                                           int perBucket) throws SQLException {
        synchronized (store) {
            List<PickFeedback.Example> result = new ArrayList<>();
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    SELECT kind, verdict, title FROM (
                        SELECT f.kind, f.verdict, fi.title,
                               ROW_NUMBER() OVER (PARTITION BY f.kind, f.verdict ORDER BY max(f.created_at) DESC) AS n
                        FROM pick_feedback f
                        JOIN item_picks p ON p.item_link = f.item_link
                        JOIN feed_items fi ON fi.item_link = f.item_link
                        WHERE p.category = ? AND f.created_at >= ?
                        GROUP BY f.item_link, f.kind, f.verdict)
                    WHERE n <= ? ORDER BY kind, verdict, n
                    """)) {
                ps.setString(1, category);
                ps.setString(2, since.toString());
                ps.setInt(3, perBucket);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new PickFeedback.Example(PickKind.of(rs.getString("kind")),
                                rs.getString("verdict"), rs.getString("title")));
                    }
                }
            }
            return result;
        }
    }

    /** Returns links some reader marked as wrongly labelled with {@code kind}. */
    public Set<String> rejected(List<String> links, PickKind kind) throws SQLException {
        synchronized (store) {
            Set<String> result = new HashSet<>();
            if (links.isEmpty()) return result;
            String placeholders = String.join(",", Collections.nCopies(links.size(), "?"));
            try (PreparedStatement ps = store.conn.prepareStatement("SELECT DISTINCT item_link FROM pick_feedback"
                    + " WHERE kind = ? AND verdict = 'down' AND item_link IN (" + placeholders + ")")) {
                ps.setString(1, kind.id());
                for (int i = 0; i < links.size(); i++) ps.setString(i + 2, links.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(rs.getString("item_link"));
                }
            }
            return result;
        }
    }

    /** Counts one reader's verdicts since {@code since}: [agreed, disagreed]. */
    public int[] countFeedback(long userId, Instant since) throws SQLException {
        synchronized (store) {
            try (PreparedStatement ps = store.conn.prepareStatement("""
                    SELECT coalesce(sum(verdict = 'up'), 0) AS up, coalesce(sum(verdict = 'down'), 0) AS down
                    FROM pick_feedback WHERE user_id = ? AND created_at >= ?
                    """)) {
                ps.setLong(1, userId);
                ps.setString(2, since.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new int[]{rs.getInt("up"), rs.getInt("down")};
                }
            }
        }
    }
}
