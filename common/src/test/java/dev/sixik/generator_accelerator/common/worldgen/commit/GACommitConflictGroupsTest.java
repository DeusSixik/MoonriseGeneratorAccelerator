package dev.sixik.generator_accelerator.common.worldgen.commit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.sixik.generator_accelerator.common.worldgen.commit.GACommitConflictGroups.GACommitConflictGranularity.BLOCK_SECTION;
import static dev.sixik.generator_accelerator.common.worldgen.commit.GACommitConflictGroups.GACommitConflictGranularity.CHUNK;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GACommitConflictGroupsTest {
    @Test
    void groupsByChunkWithStatsAndDeterministicCommandOrder() {
        GACommitCommand<String> a = command(0, 64, 0, 2, "a");
        GACommitCommand<String> b = command(1, 80, 1, 1, "b");
        GACommitCommand<String> c = command(16, 64, 0, 0, "c");

        GACommitConflictGroups<String> groups = GACommitConflictGroups.analyze(List.of(a, c, b), CHUNK);

        assertEquals(new GACommitConflictGroups.GACommitConflictStats(3, 2, 1, 2), groups.stats());
        assertEquals(new GACommitConflictGroups.GACommitConflictKey(CHUNK, 0, 0, 0), groups.groups().get(0).key());
        assertEquals(List.of(a, b), groups.groups().get(0).commands());
        assertEquals(new GACommitConflictGroups.GACommitConflictKey(CHUNK, 1, 0, 0), groups.groups().get(1).key());
        assertEquals(List.of(c), groups.groups().get(1).commands());
    }

    @Test
    void blockSectionGranularitySplitsChunkByVerticalSection() {
        GACommitCommand<String> low = command(0, 15, 0, 0, "low");
        GACommitCommand<String> highA = command(1, 16, 0, 1, "high-a");
        GACommitCommand<String> highB = command(2, 31, 0, 2, "high-b");

        GACommitConflictGroups<String> groups = GACommitConflictGroups.analyze(List.of(highB, low, highA), BLOCK_SECTION);

        assertEquals(new GACommitConflictGroups.GACommitConflictStats(3, 2, 1, 2), groups.stats());
        assertEquals(new GACommitConflictGroups.GACommitConflictKey(BLOCK_SECTION, 0, 0, 0), groups.groups().get(0).key());
        assertEquals(List.of(low), groups.groups().get(0).commands());
        assertEquals(new GACommitConflictGroups.GACommitConflictKey(BLOCK_SECTION, 0, 1, 0), groups.groups().get(1).key());
        assertEquals(List.of(highA, highB), groups.groups().get(1).commands());
    }

    private static GACommitCommand<String> command(int x, int y, int z, long sequence, String value) {
        return new GACommitCommand<>(
                new GABlockPosition(x, y, z),
                new GACommitOrderKey(0, 0, x >> 4, z >> 4, x >> 4, z >> 4, (int) sequence, sequence),
                value
        );
    }
}
