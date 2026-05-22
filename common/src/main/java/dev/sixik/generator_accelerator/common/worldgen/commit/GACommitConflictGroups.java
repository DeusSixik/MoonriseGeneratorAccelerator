package dev.sixik.generator_accelerator.common.worldgen.commit;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Coarse deterministic conflict grouping for scheduling diagnostics.
 */
public final class GACommitConflictGroups<T> {
    private final List<GACommitConflictGroup<T>> groups;
    private final GACommitConflictStats stats;

    private GACommitConflictGroups(List<GACommitConflictGroup<T>> groups, GACommitConflictStats stats) {
        this.groups = List.copyOf(groups);
        this.stats = stats;
    }

    public static <T> GACommitConflictGroups<T> analyze(
            Collection<GACommitCommand<T>> commands,
            GACommitConflictGranularity granularity
    ) {
        if (commands == null) {
            throw new NullPointerException("commands");
        }
        if (granularity == null) {
            throw new NullPointerException("granularity");
        }

        Map<GACommitConflictKey, List<GACommitCommand<T>>> byKey = new TreeMap<>();
        for (GACommitCommand<T> command : commands) {
            if (command == null) {
                throw new NullPointerException("command");
            }
            byKey.computeIfAbsent(GACommitConflictKey.from(command.position(), granularity), ignored -> new ObjectArrayList<>())
                    .add(command);
        }

        List<GACommitConflictGroup<T>> groups = new ObjectArrayList<>();
        int collidingGroups = 0;
        int largestGroupSize = 0;
        for (Map.Entry<GACommitConflictKey, List<GACommitCommand<T>>> entry : byKey.entrySet()) {
            List<GACommitCommand<T>> ordered = new ObjectArrayList<>(entry.getValue());
            ordered.sort(GACommitCommand::compareTo);
            if (ordered.size() > 1) {
                collidingGroups++;
            }
            largestGroupSize = Math.max(largestGroupSize, ordered.size());
            groups.add(new GACommitConflictGroup<>(entry.getKey(), ordered));
        }

        GACommitConflictStats stats = new GACommitConflictStats(
                commands.size(),
                groups.size(),
                collidingGroups,
                largestGroupSize
        );
        return new GACommitConflictGroups<>(groups, stats);
    }

    public List<GACommitConflictGroup<T>> groups() {
        return groups;
    }

    public GACommitConflictStats stats() {
        return stats;
    }

    public enum GACommitConflictGranularity {
        CHUNK,
        BLOCK_SECTION
    }

    public record GACommitConflictKey(
            GACommitConflictGranularity granularity,
            int chunkX,
            int sectionY,
            int chunkZ
    ) implements Comparable<GACommitConflictKey> {
        private static GACommitConflictKey from(
                GABlockPosition position,
                GACommitConflictGranularity granularity
        ) {
            int chunkX = Math.floorDiv(position.x(), 16);
            int chunkZ = Math.floorDiv(position.z(), 16);
            int sectionY = granularity == GACommitConflictGranularity.BLOCK_SECTION
                    ? Math.floorDiv(position.y(), 16)
                    : 0;
            return new GACommitConflictKey(granularity, chunkX, sectionY, chunkZ);
        }

        @Override
        public int compareTo(GACommitConflictKey other) {
            int byGranularity = granularity.compareTo(other.granularity);
            if (byGranularity != 0) {
                return byGranularity;
            }
            int byChunkX = Integer.compare(chunkX, other.chunkX);
            if (byChunkX != 0) {
                return byChunkX;
            }
            int bySectionY = Integer.compare(sectionY, other.sectionY);
            if (bySectionY != 0) {
                return bySectionY;
            }
            return Integer.compare(chunkZ, other.chunkZ);
        }
    }

    public record GACommitConflictGroup<T>(
            GACommitConflictKey key,
            List<GACommitCommand<T>> commands
    ) {
        public GACommitConflictGroup {
            if (key == null) {
                throw new NullPointerException("key");
            }
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }

    public record GACommitConflictStats(
            int commandCount,
            int groupCount,
            int collidingGroupCount,
            int largestGroupSize
    ) {
        public GACommitConflictStats {
            if (commandCount < 0) {
                throw new IllegalArgumentException("commandCount must be non-negative");
            }
            if (groupCount < 0) {
                throw new IllegalArgumentException("groupCount must be non-negative");
            }
            if (collidingGroupCount < 0) {
                throw new IllegalArgumentException("collidingGroupCount must be non-negative");
            }
            if (largestGroupSize < 0) {
                throw new IllegalArgumentException("largestGroupSize must be non-negative");
            }
        }
    }
}
