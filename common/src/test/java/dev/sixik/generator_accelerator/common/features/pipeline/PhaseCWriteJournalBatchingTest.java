package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhaseCWriteJournalBatchingTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftBootstrapHelper.ensureBootstrapped();
    }

    @Test
    void journalDeduplicatesCollidingWritesWithFirstWriteWins() {
        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        scratch.clear();

        BlockState first = Blocks.STONE.defaultBlockState();
        BlockState replacement = Blocks.GRANITE.defaultBlockState();
        scratch.beginWriteJournal();
        scratch.addDirectWrite(first, 3, 5, 7);
        scratch.addDirectWrite(Blocks.DIRT.defaultBlockState(), 3, 21, 7);
        scratch.addDirectWrite(replacement, 3, 5, 7);

        assertEquals(2, scratch.candidateCount);
        assertSame(first, scratch.candidateSimpleBlockState[0]);
        assertEquals(2, scratch.sectionBucketCount);

        scratch.finishWriteJournal();
        assertEquals(0, scratch.candidateCount);
        assertEquals(0, scratch.sectionBucketCount);
        assertEquals(0, scratch.chunkBucketCount);
    }

    @Test
    void simpleBlockBatchKeepsCollidingAttemptsInOrder() {
        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        scratch.clear();

        BlockState first = Blocks.STONE.defaultBlockState();
        BlockState replacement = Blocks.GRANITE.defaultBlockState();
        scratch.beginSimpleBlockBatch();
        scratch.addSimpleBlockCandidate(first, 3, 5, 7);
        scratch.addSimpleBlockCandidate(replacement, 3, 5, 7);

        assertEquals(2, scratch.candidateCount);
        assertSame(first, scratch.candidateSimpleBlockState[0]);
        assertSame(replacement, scratch.candidateSimpleBlockState[1]);
        assertEquals(1, scratch.sectionBucketCount);
        assertEquals(0, scratch.sectionBucketHead[0]);
        assertEquals(1, scratch.candidateNext[0]);

        scratch.finishSimpleBlockBatch();
    }

    @Test
    void journalGroupsWritesByChunkThenSection() {
        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        scratch.clear();

        scratch.beginWriteJournal();
        scratch.addDirectWrite(Blocks.STONE.defaultBlockState(), 0, 4, 0);
        scratch.addDirectWrite(Blocks.DIRT.defaultBlockState(), 1, 5, 0);
        scratch.addDirectWrite(Blocks.GRANITE.defaultBlockState(), 0, 20, 0);
        scratch.addDirectWrite(Blocks.ANDESITE.defaultBlockState(), 17, 4, 0);

        assertEquals(4, scratch.candidateCount);
        assertEquals(3, scratch.sectionBucketCount);
        assertEquals(2, scratch.chunkBucketCount);
        assertTrue(scratch.chunkBucketHead[0] >= 0);
        assertTrue(scratch.chunkBucketHead[1] >= 0);

        scratch.finishWriteJournal();
    }

    @Test
    void touchedMutationsCollapseToSectionColumns() {
        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        scratch.clear();
        ChunkAccess chunk = chunkAt(new ChunkPos(0, 0));

        scratch.noteJournalMutation(chunk, 2, 3, 4);
        scratch.noteJournalMutation(chunk, 2, 12, 4);
        scratch.noteJournalMutation(chunk, 2, 20, 4);
        scratch.noteJournalMutation(chunk, 3, 3, 4);

        assertEquals(3, scratch.touchedMutationCount());

        scratch.finishWriteJournal();
    }

    @Test
    void journalBuffersGrowOnceAndAreRetainedForReuse() {
        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        scratch.clear();

        scratch.beginWriteJournal();
        for (int i = 0; i < 300; i++) {
            scratch.addDirectWrite(Blocks.STONE.defaultBlockState(), i, 4, 0);
        }
        int grownCandidateCapacity = scratch.candidateX.length;
        int grownSectionCapacity = scratch.sectionBucketKey.length;
        assertTrue(grownCandidateCapacity >= 300);
        assertTrue(grownSectionCapacity >= 19);

        scratch.finishWriteJournal();
        scratch.beginWriteJournal();
        scratch.addDirectWrite(Blocks.DIRT.defaultBlockState(), 0, 4, 0);

        assertEquals(grownCandidateCapacity, scratch.candidateX.length);
        assertEquals(grownSectionCapacity, scratch.sectionBucketKey.length);

        scratch.finishWriteJournal();
    }

    private static ChunkAccess chunkAt(ChunkPos pos) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(pos);
        return chunk;
    }
}
