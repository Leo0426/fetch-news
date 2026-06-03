package app.reader;

import java.util.List;

/**
 * One source group on the reader page.
 *
 * @param name  human-readable source name (e.g. "HN · top", "BBC · technology")
 * @param tag   broad category tag shown as a chip (e.g. "编程", "英文媒体")
 * @param items feed items belonging to this source
 */
public record ReaderGroup(String name, String tag, List<ReaderItem> items) {}
