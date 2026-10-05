package app.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FetchResponse(int statusCode, Map<String, List<String>> headers, String body) {
    public FetchResponse {
        Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
        headers.forEach((name, values) -> copiedHeaders.put(name, List.copyOf(values)));
        headers = Map.copyOf(copiedHeaders);
    }

    public String header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .filter(values -> !values.isEmpty())
                .map(List::getFirst)
                .findFirst()
                .orElse(null);
    }
}
