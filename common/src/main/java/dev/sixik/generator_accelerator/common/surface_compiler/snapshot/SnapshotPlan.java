package dev.sixik.generator_accelerator.common.surface_compiler.snapshot;

import java.util.Set;

public record SnapshotPlan(SnapshotDomain domain, boolean needsPreMutationStorage, boolean fallbackIfUnavailable) {
    public static SnapshotPlan none() {
        return new SnapshotPlan(SnapshotDomain.NO_SNAPSHOT, false, false);
    }

    public static SnapshotPlan vanillaOnly() {
        return new SnapshotPlan(SnapshotDomain.VANILLA_ONLY, true, true);
    }

    public static SnapshotPlan sectionBorrow() {
        return new SnapshotPlan(SnapshotDomain.SECTION_BORROW, true, true);
    }

    public static SnapshotPlan sectionCopyOnRead() {
        return new SnapshotPlan(SnapshotDomain.SECTION_COPY_ON_READ, true, true);
    }

    public static SnapshotPlan forDomains(Set<String> domains, boolean orderedState) {
        if (domains == null || domains.isEmpty() || domains.equals(Set.of("CONSTANT"))) {
            return none();
        }
        if (domains.contains("HALO")) {
            return new SnapshotPlan(SnapshotDomain.HALO_READ_ONLY, true, true);
        }
        if (domains.contains("WATER") || domains.contains("STONE_DEPTH")) {
            return new SnapshotPlan(SnapshotDomain.COLUMN_BAND_COPY, true, true);
        }
        if (domains.contains("Y_BAND") || domains.contains("BIOME") || domains.contains("NOISE") || orderedState) {
            return sectionBorrow();
        }
        return sectionCopyOnRead();
    }

    public enum SnapshotDomain {
        NO_SNAPSHOT,
        COLUMN_FACTS,
        SECTION_BORROW,
        SECTION_COPY_ON_READ,
        COLUMN_BAND_COPY,
        HALO_READ_ONLY,
        VANILLA_ONLY
    }
}
