package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecorationPipelineScratchRetentionTest {

    @Test
    void candidateBuffersStayWarmAcrossModeratelyLargeChunks() {
        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        scratch.clear();

        scratch.ensureCandidateCapacity(70_000);
        int[] retainedCandidates = scratch.candidateX;
        int retainedLength = retainedCandidates.length;

        scratch.clear();

        assertSame(retainedCandidates, scratch.candidateX);
        assertEquals(retainedLength, scratch.candidateX.length);

        scratch.ensureCandidateCapacity(70_000);
        assertSame(retainedCandidates, scratch.candidateX);
    }

    @Test
    void candidateBuffersTrimOnlyAfterExcessiveGrowth() {
        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        scratch.clear();

        scratch.ensureCandidateCapacity(300_000);
        assertTrue(scratch.candidateX.length >= 300_000);

        scratch.clear();

        assertEquals(65_536, scratch.candidateX.length);
        assertEquals(65_536, scratch.selectedFeatureBuffer.length);
    }

    @Test
    void oreVisitedWordsAreReusedBetweenComparablePlacements() {
        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        scratch.clear();

        long[] first = scratch.clearOreVisitedWords(524_288);
        assertTrue(first.length >= 8_192);

        scratch.clear();

        long[] second = scratch.clearOreVisitedWords(524_288);
        assertSame(first, second);
    }

    @Test
    void longScratchBufferTrimsOnlyWhenTrulyHuge() {
        LongScratchBuffer buffer = new LongScratchBuffer(32);
        for (int i = 0; i < 400_000; i++) {
            buffer.add(i);
        }
        long[] retained = buffer.elements();

        buffer.clear();
        assertSame(retained, buffer.elements());

        for (int i = 0; i < 1_100_000; i++) {
            buffer.add(i);
        }
        assertTrue(buffer.elements().length > 1_048_576);

        buffer.clear();
        buffer.trimIfExcessivelyOversized();
        assertEquals(262_144, buffer.elements().length);
    }
}
