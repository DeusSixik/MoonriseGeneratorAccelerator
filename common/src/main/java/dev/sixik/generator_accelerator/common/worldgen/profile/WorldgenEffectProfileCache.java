package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class WorldgenEffectProfileCache {
    private static final WorldgenEffectProfileCache GLOBAL = new WorldgenEffectProfileCache(new WorldgenEffectScanner());

    private final WorldgenEffectScanner scanner;
    private final ConcurrentHashMap<Key, WorldgenEffectProfile> profiles = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public WorldgenEffectProfileCache(WorldgenEffectScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    public static WorldgenEffectProfileCache global() {
        return GLOBAL;
    }

    public WorldgenEffectProfile profile(Class<?> unitClass, String methodHint) {
        Key key = Key.of(unitClass, methodHint);
        WorldgenEffectProfile cached = this.profiles.get(key);
        if (cached != null) {
            this.hits.incrementAndGet();
            return cached;
        }
        this.misses.incrementAndGet();
        WorldgenEffectProfile scanned = this.scanner.scan(unitClass, methodHint);
        WorldgenEffectProfile raced = this.profiles.putIfAbsent(key, scanned);
        return raced == null ? scanned : raced;
    }

    public int size() {
        return this.profiles.size();
    }

    public long hits() {
        return this.hits.get();
    }

    public long misses() {
        return this.misses.get();
    }

    public void clear() {
        this.profiles.clear();
        this.hits.set(0L);
        this.misses.set(0L);
    }

    private record Key(String className, String methodHint, ClassLoader loader) {
        static Key of(Class<?> unitClass, String methodHint) {
            return new Key(
                    unitClass == null ? "" : unitClass.getName(),
                    methodHint == null ? "" : methodHint,
                    unitClass == null ? null : unitClass.getClassLoader()
            );
        }
    }
}
