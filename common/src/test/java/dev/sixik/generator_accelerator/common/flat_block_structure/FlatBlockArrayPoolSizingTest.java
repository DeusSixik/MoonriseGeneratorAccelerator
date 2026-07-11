package dev.sixik.generator_accelerator.common.flat_block_structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlatBlockArrayPoolSizingTest {

    @Test
    void rawPoolTracksWorkspaceConcurrencyInsteadOfGlobalChunkVolume() {
        assertEquals(32, FlatBlockArrayPoolSizing.defaultRawPoolMax(1, 0));
        assertEquals(96, FlatBlockArrayPoolSizing.defaultRawPoolMax(8, 0));
        assertEquals(192, FlatBlockArrayPoolSizing.defaultRawPoolMax(64, 8));
        assertEquals(1024, FlatBlockArrayPoolSizing.defaultRawPoolMax(256, 256));
    }

    @Test
    void eagerAllocationRemainsSmall() {
        assertEquals(8, FlatBlockArrayPoolSizing.defaultPrealloc(1024));
        assertEquals(4, FlatBlockArrayPoolSizing.defaultPrealloc(4));
        assertEquals(256, FlatBlockArrayPoolSizing.defaultDirtyIndexCapacity());
    }
}
