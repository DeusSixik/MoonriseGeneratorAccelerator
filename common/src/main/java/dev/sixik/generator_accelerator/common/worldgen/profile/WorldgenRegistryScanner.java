package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class WorldgenRegistryScanner {
    private final ConcurrentHashMap<CacheKey, WorldgenUnitProfile> profileCache = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong cacheEpoch = new AtomicLong(Long.MIN_VALUE);

    public long currentEpoch() {
        return this.epoch.get();
    }

    public long nextEpoch() {
        return this.epoch.incrementAndGet();
    }

    public void resetEpoch(long epoch) {
        this.epoch.set(epoch);
    }

    public int cachedProfiles() {
        return this.profileCache.size();
    }

    public void clear() {
        this.profileCache.clear();
        this.cacheEpoch.set(Long.MIN_VALUE);
    }

    public void reset() {
        this.epoch.set(0L);
        clear();
    }

    public WorldgenRegistryScan scan(RegistrySource... sources) {
        return scan(this.epoch.get(), sources);
    }

    public WorldgenRegistryScan scan(long epoch, RegistrySource... sources) {
        replaceCacheEpoch(epoch);
        if (sources == null || sources.length == 0) {
            return WorldgenRegistryScan.empty(epoch);
        }

        ScanBuilder builder = new ScanBuilder(epoch);
        for (RegistrySource source : sources) {
            scanSource(builder, source);
        }
        return builder.build();
    }

    private void replaceCacheEpoch(long epoch) {
        long cached = this.cacheEpoch.get();
        if (cached == epoch) {
            return;
        }
        this.profileCache.clear();
        this.cacheEpoch.set(epoch);
    }

    public WorldgenRegistryScan scanMap(long epoch, WorldgenUnitKind kind, String registryName, Map<?, ?> units) {
        return scan(epoch, RegistrySource.map(kind, registryName, units));
    }

    public WorldgenRegistryScan scanIterable(long epoch, WorldgenUnitKind kind, String registryName, Iterable<?> units) {
        return scan(epoch, RegistrySource.iterable(kind, registryName, units));
    }

    public WorldgenRegistryScan scanRegistry(long epoch, WorldgenUnitKind kind, String registryName, Object registry) {
        return scan(epoch, RegistrySource.registry(kind, registryName, registry));
    }

    private void scanSource(ScanBuilder builder, RegistrySource source) {
        if (source == null) {
            return;
        }
        Iterable<?> entries = entriesOf(source.contents());
        if (entries == null) {
            builder.add(classifyFailure(builder.epoch, source.kind(), source.registryName(), "unreadable registry source"));
            return;
        }

        Iterator<?> iterator;
        try {
            iterator = entries.iterator();
        } catch (RuntimeException failure) {
            builder.add(classifyFailure(builder.epoch, source.kind(), source.registryName(), "registry iterator failed"));
            return;
        }

        while (true) {
            Object entry;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                entry = iterator.next();
            } catch (RuntimeException failure) {
                builder.add(classifyFailure(builder.epoch, source.kind(), source.registryName(), "registry entry iteration failed"));
                break;
            }
            scanEntry(builder, source.kind(), source.registryName(), entry);
        }
    }

    private void scanEntry(ScanBuilder builder, WorldgenUnitKind kind, String registryName, Object entry) {
        String id = registryName;
        Object value = entry;
        if (entry instanceof Map.Entry<?, ?> mapEntry) {
            id = safeId(mapEntry.getKey(), registryName);
            value = safeValue(mapEntry);
        }

        CacheKey key = CacheKey.of(builder.epoch, kind, id, value);
        WorldgenUnitProfile cached = this.profileCache.get(key);
        if (cached != null) {
            builder.cacheHits++;
            builder.add(cached);
            return;
        }

        builder.cacheMisses++;
        WorldgenUnitProfile profile;
        try {
            profile = withEpoch(WorldgenUnitClassifier.classifyUnitUnrecorded(kind, id, value), builder.epoch);
        } catch (RuntimeException failure) {
            profile = classifyFailure(builder.epoch, kind, id, "registry classification failed");
        }
        this.profileCache.put(key, profile);
        builder.add(profile);
    }

    private static Object safeValue(Map.Entry<?, ?> entry) {
        try {
            return entry.getValue();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static Iterable<?> entriesOf(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof Map<?, ?> map) {
            return map.entrySet();
        }
        if (source instanceof Iterable<?> iterable) {
            return iterable;
        }
        Iterable<?> reflected = reflectEntries(source, "entrySet");
        return reflected == null ? reflectEntries(source, "entries") : reflected;
    }

    private static Iterable<?> reflectEntries(Object source, String methodName) {
        try {
            Method method = source.getClass().getMethod(methodName);
            Object entries = method.invoke(source);
            if (entries instanceof Map<?, ?> map) {
                return map.entrySet();
            }
            if (entries instanceof Iterable<?> iterable) {
                return iterable;
            }
            return null;
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException failure) {
            return null;
        }
    }

    private static WorldgenUnitProfile classifyFailure(long epoch, WorldgenUnitKind kind, String id, String reason) {
        WorldgenUnitKind effectiveKind = kind == null ? WorldgenUnitKind.UNKNOWN : kind;
        String namespace = WorldgenUnitClassifier.namespaceOfId(id);
        if (namespace.isBlank()) {
            namespace = "minecraft";
        }
        return new WorldgenUnitProfile(
                prefix(effectiveKind, id),
                namespace,
                Object.class.getName(),
                "",
                "",
                epoch,
                effectiveKind.defaultEntryPoint(),
                0,
                java.util.EnumSet.of(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD),
                WorldgenSafetyTier.VANILLA_FALLBACK_DISABLED,
                List.of(),
                reason
        );
    }

    private static WorldgenUnitProfile withEpoch(WorldgenUnitProfile profile, long epoch) {
        if (profile == null) {
            return classifyFailure(epoch, WorldgenUnitKind.UNKNOWN, "", "null profile");
        }
        return new WorldgenUnitProfile(
                profile.id(),
                profile.namespace(),
                profile.className(),
                profile.bytecodeHash(),
                profile.configHash(),
                epoch,
                profile.entryPointMethod(),
                profile.estimatedCost(),
                profile.effectFlags(),
                profile.safetyTier(),
                profile.guards(),
                profile.fallbackReason()
        );
    }

    private static String safeId(Object key, String fallback) {
        try {
            String id = String.valueOf(key);
            return id == null || id.isBlank() ? fallback : id;
        } catch (RuntimeException failure) {
            return fallback == null ? "" : fallback;
        }
    }

    private static String prefix(WorldgenUnitKind kind, String id) {
        String normalized = kind.name().toLowerCase(java.util.Locale.ROOT);
        return id == null || id.isBlank() ? normalized + ":unknown" : normalized + ":" + id;
    }

    private record CacheKey(
            long epoch,
            WorldgenUnitKind kind,
            String id,
            Class<?> valueClass,
            int identityHash
    ) {
        static CacheKey of(long epoch, WorldgenUnitKind kind, String id, Object value) {
            Class<?> valueClass = value == null ? Object.class : value.getClass();
            return new CacheKey(
                    epoch,
                    kind == null ? WorldgenUnitKind.UNKNOWN : kind,
                    id == null ? "" : id,
                    valueClass,
                    System.identityHashCode(value)
            );
        }
    }

    public record RegistrySource(WorldgenUnitKind kind, String registryName, Object contents) {
        public RegistrySource {
            kind = kind == null ? WorldgenUnitKind.UNKNOWN : kind;
            registryName = registryName == null ? "" : registryName;
        }

        public static RegistrySource map(WorldgenUnitKind kind, String registryName, Map<?, ?> contents) {
            return new RegistrySource(kind, registryName, contents);
        }

        public static RegistrySource iterable(WorldgenUnitKind kind, String registryName, Iterable<?> contents) {
            return new RegistrySource(kind, registryName, contents);
        }

        public static RegistrySource registry(WorldgenUnitKind kind, String registryName, Object contents) {
            return new RegistrySource(kind, registryName, contents);
        }
    }

    private static final class ScanBuilder {
        private final long epoch;
        private final ArrayList<WorldgenUnitProfile> profiles = new ArrayList<>();
        private final EnumMap<WorldgenUnitKind, Long> countsByKind = new EnumMap<>(WorldgenUnitKind.class);
        private final EnumMap<WorldgenSafetyTier, Long> countsByTier = new EnumMap<>(WorldgenSafetyTier.class);
        private final LinkedHashMap<String, Long> countsByNamespace = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> countsByFallbackReason = new LinkedHashMap<>();
        private long cacheHits;
        private long cacheMisses;

        private ScanBuilder(long epoch) {
            this.epoch = epoch;
        }

        private void add(WorldgenUnitProfile profile) {
            if (profile == null) {
                return;
            }
            this.profiles.add(profile);
            WorldgenUnitKind kind = kindOf(profile);
            increment(this.countsByKind, kind);
            increment(this.countsByTier, profile.safetyTier());
            increment(this.countsByNamespace, profile.namespace().isBlank() ? "unknown" : profile.namespace());
            if (!profile.fallbackReason().isBlank()) {
                increment(this.countsByFallbackReason, profile.fallbackReason());
            }
            WorldgenProfileMetrics.record(profile);
        }

        private WorldgenRegistryScan build() {
            return new WorldgenRegistryScan(
                    this.epoch,
                    this.profiles,
                    this.countsByKind,
                    this.countsByTier,
                    this.countsByNamespace,
                    this.countsByFallbackReason,
                    this.cacheHits,
                    this.cacheMisses
            );
        }

        private static WorldgenUnitKind kindOf(WorldgenUnitProfile profile) {
            String id = profile.id();
            if (id != null) {
                String lower = id.toLowerCase(java.util.Locale.ROOT);
                for (WorldgenUnitKind kind : WorldgenUnitKind.values()) {
                    if (lower.startsWith(kind.name().toLowerCase(java.util.Locale.ROOT) + ":")) {
                        return kind;
                    }
                }
            }
            return WorldgenUnitKind.UNKNOWN;
        }

        private static <K> void increment(Map<K, Long> counts, K key) {
            counts.merge(Objects.requireNonNull(key), 1L, Long::sum);
        }
    }
}
