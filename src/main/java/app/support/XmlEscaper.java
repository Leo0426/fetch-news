package app.support;

/**
 * Escapes text for safe inclusion in XML elements and attributes.
 */
public final class XmlEscaper {
    /**
     * Prevents construction of this utility class.
     */
    private XmlEscaper() {}

    /**
     * Escapes XML special characters.
     *
     * @param value raw value, possibly {@code null}
     * @return escaped XML text
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
