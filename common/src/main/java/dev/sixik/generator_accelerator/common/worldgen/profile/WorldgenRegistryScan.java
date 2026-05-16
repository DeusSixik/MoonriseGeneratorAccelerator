package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorldgenRegistryScan(
        long epoch,
        List<WorldgenUnitProfile> profiles,
        Map<WorldgenUnitKind, Long> countsByKind,
        Map<WorldgenSafetyTier, Long> countsByTier,
        Map<String, Long> countsByNamespace,
        Map<String, Long> countsByFallbackReason,
        long cacheHits,
        long cacheMisses
) {
    public WorldgenRegistryScan {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        countsByKind = immutableEnumCounts(WorldgenUnitKind.class, countsByKind);
        countsByTier = immutableEnumCounts(WorldgenSafetyTier.class, countsByTier);
        countsByNamespace = immutableStringCounts(countsByNamespace);
        countsByFallbackReason = immutableStringCounts(countsByFallbackReason);
    }

    public long totalUnits() {
        return this.profiles.size();
    }

    public long count(WorldgenUnitKind kind) {
        return this.countsByKind.getOrDefault(kind, 0L);
    }

    public long count(WorldgenSafetyTier tier) {
        return this.countsByTier.getOrDefault(tier, 0L);
    }

    public long countNamespace(String namespace) {
        return this.countsByNamespace.getOrDefault(namespace == null || namespace.isBlank() ? "unknown" : namespace, 0L);
    }

    public long countFallback(String fallbackReason) {
        return this.countsByFallbackReason.getOrDefault(fallbackReason == null ? "" : fallbackReason, 0L);
    }

    static WorldgenRegistryScan empty(long epoch) {
        return new WorldgenRegistryScan(epoch, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0L, 0L);
    }

    private static <E extends Enum<E>> Map<E, Long> immutableEnumCounts(Class<E> enumClass, Map<E, Long> source) {
        EnumMap<E, Long> out = new EnumMap<>(enumClass);
        for (E value : enumClass.getEnumConstants()) {
            long count = source == null ? 0L : Math.max(0L, source.getOrDefault(value, 0L));
            out.put(value, count);
        }
        return Map.copyOf(out);
    }

    private static Map<String, Long> immutableStringCounts(Map<String, Long> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(key == null || key.isBlank() ? "unknown" : key, Math.max(0L, value)));
        return Map.copyOf(out);
    }
}
