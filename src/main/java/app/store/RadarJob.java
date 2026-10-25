package app.store;

/** A durable radar background job; {@code payload} is the job's JSON input, replaced by its result. */
public record RadarJob(String id, String kind, String requestKey, String state, String payload, long createdAt) {}
