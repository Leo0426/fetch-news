package app.ai;

/** Some text-only gateways include reasoning tags or a Markdown fence in the assistant text. */
final class ModelOutput {
    private ModelOutput() {}

    static String normalize(String text, boolean json) {
        String value = text.strip();
        if (value.startsWith("<think>")) {
            int end = value.indexOf("</think>", "<think>".length());
            if (end < 0) throw new IllegalStateException("AI response did not complete");
            value = value.substring(end + "</think>".length()).strip();
        }
        if (!json || !value.startsWith("```")) return value;
        int firstBreak = value.indexOf('\n');
        int lastBreak = value.lastIndexOf('\n');
        if (firstBreak < 0 || lastBreak <= firstBreak || !value.substring(lastBreak + 1).strip().equals("```"))
            return value;
        String marker = value.substring(0, firstBreak).stripTrailing();
        return marker.equals("```") || marker.equalsIgnoreCase("```json")
                ? value.substring(firstBreak + 1, lastBreak).strip() : value;
    }
}
