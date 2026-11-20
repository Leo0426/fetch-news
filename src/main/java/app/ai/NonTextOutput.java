package app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.regex.Pattern;

/** Classifies explicit endpoint or output evidence without inferring capabilities from a model ID. */
enum NonTextOutput {
    EMBEDDING, IMAGE, AUDIO, VIDEO, RANKING, MEDIA;

    private static final Pattern ENDPOINT_HINT = Pattern.compile(
            "(?:use|requires?|(?:supported|available) (?:at|on|in)) (?:the )?[`'\"]?(?:/?v[0-9]+/|/)"
                    + "(embeddings|images/generations|images/edits|audio/speech|audio/transcriptions|audio/translations"
                    + "|videos|rerank)\\b");
    private static final Pattern ONLY_OUTPUT = Pattern.compile(
            "only supports? (?:output (?:type|modality) )?[\\[`'\"]*(embeddings?|images?|audio|video)"
                    + "(?: generation| outputs?)?[`'\"\\]]*(?:[.!?;]|$)");

    String reason() {
        return this == MEDIA ? "text" : name().toLowerCase(Locale.ROOT);
    }

    static NonTextOutput contentType(String type) {
        if (type.startsWith("audio/")) return AUDIO;
        if (type.startsWith("image/")) return IMAGE;
        return type.startsWith("video/") ? VIDEO : null;
    }

    static NonTextOutput rejection(String message) {
        var endpoint = ENDPOINT_HINT.matcher(message);
        if (endpoint.find()) {
            String path = endpoint.group(1);
            if (path.startsWith("audio/")) return AUDIO;
            if (path.startsWith("images/")) return IMAGE;
            if (path.equals("embeddings")) return EMBEDDING;
            return path.equals("videos") ? VIDEO : RANKING;
        }
        var only = ONLY_OUTPUT.matcher(message);
        if (only.find()) {
            String output = only.group(1);
            if (output.startsWith("embedding")) return EMBEDDING;
            if (output.startsWith("image")) return IMAGE;
            return output.equals("audio") ? AUDIO : VIDEO;
        }
        if (message.contains("is an embedding model") || message.contains("is an embedding-only model"))
            return EMBEDDING;
        if (message.contains("is an image generation model") || message.contains("is an image-only model"))
            return IMAGE;
        if (message.contains("is a text-to-speech model") || message.contains("is an audio-only model")) return AUDIO;
        if (message.contains("is a video generation model")) return VIDEO;
        if (message.contains("is a reranking model")) return RANKING;
        return null;
    }

    static NonTextOutput result(JsonNode response) {
        if (response.path("embeddings").isArray() || response.path("embedding").isArray()
                || response.at("/embedding/values").isArray() || response.at("/data/0/embedding").isArray()
                || "embedding".equals(response.at("/data/0/object").textValue())) return EMBEDDING;
        if (response.at("/data/0/b64_json").isTextual() || response.at("/data/0/url").isTextual()) return IMAGE;
        if (response.at("/results/0/relevance_score").isNumber()) return RANKING;
        return null;
    }

    static NonTextOutput media(JsonNode response) {
        JsonNode message = response.at("/choices/0/message");
        if (message.hasNonNull("audio")) return AUDIO;
        NonTextOutput kind = parts(message.path("content"));
        if (kind != null) return kind;
        kind = parts(response.at("/candidates/0/content/parts"));
        if (kind != null) return kind;
        kind = parts(response.path("content"));
        if (kind != null) return kind;
        for (var item : response.path("output")) {
            if (item.path("type").asText().equals("image_generation_call")) return IMAGE;
            kind = parts(item.path("content"));
            if (kind != null) return kind;
        }
        return null;
    }

    static NonTextOutput parts(JsonNode parts) {
        for (var part : parts) {
            if (part.hasNonNull("inlineData") || part.hasNonNull("fileData")) {
                JsonNode data = part.hasNonNull("inlineData") ? part.get("inlineData") : part.get("fileData");
                NonTextOutput kind = contentType(data.path("mimeType").asText("").toLowerCase(Locale.ROOT));
                return kind != null ? kind : MEDIA;
            }
            switch (part.path("type").asText()) {
                case "image", "image_url", "output_image" -> { return IMAGE; }
                case "audio", "output_audio" -> { return AUDIO; }
                case "video", "output_video" -> { return VIDEO; }
            }
        }
        return null;
    }
}
