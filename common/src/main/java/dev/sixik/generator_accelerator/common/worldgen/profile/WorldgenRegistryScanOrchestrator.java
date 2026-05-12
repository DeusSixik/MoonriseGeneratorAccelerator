package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reload-ready owner for cheap worldgen registry preflight scans. Callers pass
 * registry sources from a loader/datapack reload hook and atomically publish the
 * resulting profile snapshot for diagnostics or staged rollout decisions.
 */
public final class WorldgenRegistryScanOrchestrator {
    public static final WorldgenRegistryScanOrchestrator GLOBAL = new WorldgenRegistryScanOrchestrator(new WorldgenRegistryScanner());

    private final WorldgenRegistryScanner scanner;
    private final AtomicReference<WorldgenRegistryScan> currentScan = new AtomicReference<>(WorldgenRegistryScan.empty(0L));
    private final CopyOnWriteArrayList<ScanListener> listeners = new CopyOnWriteArrayList<>();

    public WorldgenRegistryScanOrchestrator(WorldgenRegistryScanner scanner) {
        this.scanner = scanner == null ? new WorldgenRegistryScanner() : scanner;
    }

    public WorldgenRegistryScan currentScan() {
        return this.currentScan.get();
    }

    public long currentEpoch() {
        return this.scanner.currentEpoch();
    }

    public int cachedProfiles() {
        return this.scanner.cachedProfiles();
    }

    public void addListener(ScanListener listener) {
        if (listener != null) {
            this.listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(ScanListener listener) {
        this.listeners.remove(listener);
    }

    public WorldgenRegistryScan runtimeScan(WorldgenRegistryScanner.RegistrySource... sources) {
        return publish(this.scanner.scan(this.scanner.currentEpoch(), sources), false);
    }

    public WorldgenRegistryScan reloadScan(WorldgenRegistryScanner.RegistrySource... sources) {
        long epoch = this.scanner.nextEpoch();
        return publish(this.scanner.scan(epoch, sources), true);
    }

    public WorldgenRegistryScan reloadScan(List<WorldgenRegistryScanner.RegistrySource> sources) {
        return reloadScan(toArray(sources));
    }

    public WorldgenRegistryScan runtimeScan(List<WorldgenRegistryScanner.RegistrySource> sources) {
        return runtimeScan(toArray(sources));
    }

    public Map<String, Object> snapshot() {
        WorldgenRegistryScan scan = currentScan();
        return Map.of(
                "epoch", scan.epoch(),
                "totalUnits", scan.totalUnits(),
                "cacheHits", scan.cacheHits(),
                "cacheMisses", scan.cacheMisses(),
                "cachedProfiles", cachedProfiles(),
                "countsByKind", scan.countsByKind(),
                "countsByTier", scan.countsByTier(),
                "countsByNamespace", scan.countsByNamespace(),
                "countsByFallbackReason", scan.countsByFallbackReason()
        );
    }

    public void reset() {
        this.scanner.reset();
        this.currentScan.set(WorldgenRegistryScan.empty(0L));
    }

    private WorldgenRegistryScan publish(WorldgenRegistryScan scan, boolean reload) {
        this.currentScan.set(scan);
        WorldgenProfileMetrics.recordRegistryScan(scan, reload);
        for (ScanListener listener : this.listeners) {
            try {
                listener.onScan(scan, reload);
            } catch (RuntimeException failure) {
                WorldgenProfileMetrics.recordRegistryScanListenerFailure();
            }
        }
        return scan;
    }

    private static WorldgenRegistryScanner.RegistrySource[] toArray(List<WorldgenRegistryScanner.RegistrySource> sources) {
        if (sources == null || sources.isEmpty()) {
            return new WorldgenRegistryScanner.RegistrySource[0];
        }
        return sources.toArray(WorldgenRegistryScanner.RegistrySource[]::new);
    }

    @FunctionalInterface
    public interface ScanListener {
        void onScan(WorldgenRegistryScan scan, boolean reload);
    }
}
