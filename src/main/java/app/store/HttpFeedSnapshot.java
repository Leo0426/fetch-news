package app.store;

public record HttpFeedSnapshot(String url, String body, String etag, String lastModified) {}
