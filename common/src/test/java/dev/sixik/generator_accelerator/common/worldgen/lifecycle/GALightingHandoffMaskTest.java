package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GALightingHandoffMaskTest {
    @Test
    void buildsStableMaskFromDirtyColumns() {
        GALightingHandoffMask mask = GALightingHandoffMask.fromDirtyColumns(List.of(
                new GAColumnPosition(3, 1),
                new GAColumnPosition(0, 0),
                new GAColumnPosition(3, 1),
                new GAColumnPosition(15, 15)
        ));

        assertFalse(mask.isEmpty());
        assertEquals(3, mask.dirtyColumnCount());
        assertTrue(mask.contains(new GAColumnPosition(3, 1)));
        assertFalse(mask.contains(new GAColumnPosition(4, 1)));
        assertEquals(List.of(
                new GAColumnPosition(0, 0),
                new GAColumnPosition(3, 1),
                new GAColumnPosition(15, 15)
        ), mask.dirtyColumns());
    }

    @Test
    void rejectsColumnsOutsideChunkMask() {
        BitSet bits = new BitSet();
        bits.set(256);

        assertThrows(IllegalArgumentException.class, () -> GALightingHandoffMask.fromDirtyColumnBits(bits));
        assertThrows(IllegalArgumentException.class, () -> new GAColumnPosition(16, 0));
        assertThrows(IllegalArgumentException.class, () -> GAColumnPosition.fromPackedIndex(-1));
    }

    @Test
    void exposesDefensiveBitSetCopy() {
        BitSet bits = new BitSet();
        bits.set(5);
        GALightingHandoffMask mask = GALightingHandoffMask.fromDirtyColumnBits(bits);

        bits.set(6);
        BitSet exported = mask.toBitSet();
        exported.clear(5);

        assertEquals(1, mask.dirtyColumnCount());
        assertTrue(mask.contains(GAColumnPosition.fromPackedIndex(5)));
        assertFalse(mask.contains(GAColumnPosition.fromPackedIndex(6)));
    }
}
