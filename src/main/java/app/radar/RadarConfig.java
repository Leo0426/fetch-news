package app.radar;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.scheduling.support.CronExpression;

public record RadarConfig(boolean enabled, String zoneId, String scheduleCron, String defaultProfile,
                          int candidateLimit, int maxItems, int maxModelCalls, int runTimeoutMinutes,
                          Map<String, ProfileSettings> profileSettings, Map<String, SourceBinding> sources,
                          Map<String, CategoryGroup> categoryGroups, int defaultGroupLimit) {
    public RadarConfig {
        zoneId = zoneId == null ? "Asia/Shanghai" : zoneId;
        ZoneId.of(zoneId);
        scheduleCron = scheduleCron == null ? "" : scheduleCron;
        if (!scheduleCron.isBlank()) CronExpression.parse(scheduleCron);
        defaultProfile = defaultProfile == null ? "tech-news" : defaultProfile;
        candidateLimit = candidateLimit == 0 ? 200 : candidateLimit;
        maxItems = maxItems == 0 ? 20 : maxItems;
        maxModelCalls = maxModelCalls == 0 ? 500 : maxModelCalls;
        runTimeoutMinutes = runTimeoutMinutes == 0 ? 60 : runTimeoutMinutes;
        if (candidateLimit < 1 || candidateLimit > 10000 || maxItems < 1 || maxModelCalls < 1
                || runTimeoutMinutes < 1 || defaultGroupLimit < 0) throw new IllegalArgumentException("Invalid radar limits");
        profileSettings = profileSettings == null ? Map.of() : Map.copyOf(profileSettings);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
        categoryGroups = categoryGroups == null ? Map.of() : Map.copyOf(categoryGroups);
        Set<String> categories = new HashSet<>();
        for (var group : categoryGroups.values()) {
            for (String category : group.categories()) {
                if (!categories.add(category)) throw new IllegalArgumentException("Overlapping category: " + category);
            }
        }
        for (String path : sources.keySet()) {
            if (!path.startsWith("/")) throw new IllegalArgumentException("Source must be a mount path: " + path);
        }
    }

    public static RadarConfig defaults() {
        return new RadarConfig(false, null, null, null, 0, 0, 0, 0, null, null, null, 0);
    }

    public ProfileSettings settings(String profile) {
        return profileSettings.getOrDefault(profile, new ProfileSettings(7.0, true));
    }

    public void validateProfiles(Set<String> available) {
        if (!available.contains(defaultProfile)) throw new IllegalArgumentException("Unknown default Profile: " + defaultProfile);
        for (String id : profileSettings.keySet()) {
            if (!available.contains(id)) throw new IllegalArgumentException("Unknown Profile: " + id);
        }
        for (var binding : sources.values()) {
            for (String id : binding.profiles()) {
                if (!id.equals("auto") && !available.contains(id)) throw new IllegalArgumentException("Unknown Profile: " + id);
            }
        }
    }

    public record ProfileSettings(double threshold, boolean topicDedup) {
        public ProfileSettings {
            if (!Double.isFinite(threshold) || threshold < 0 || threshold > 10)
                throw new IllegalArgumentException("Profile threshold must be between 0 and 10");
        }
    }

    public record SourceBinding(List<String> profiles, String category) {
        public SourceBinding {
            if (profiles == null || profiles.isEmpty()) throw new IllegalArgumentException("Profile candidates cannot be empty");
            profiles = List.copyOf(profiles);
            if (profiles.stream().anyMatch(String::isBlank) || new HashSet<>(profiles).size() != profiles.size()
                    || (profiles.contains("auto") && profiles.size() != 1))
                throw new IllegalArgumentException("Invalid Profile candidates");
            category = category == null ? "" : category;
        }
    }

    public record CategoryGroup(int limit, List<String> categories) {
        public CategoryGroup {
            if (limit < 1 || categories == null || categories.isEmpty()) throw new IllegalArgumentException("Invalid category group");
            categories = List.copyOf(categories);
        }
    }
}
