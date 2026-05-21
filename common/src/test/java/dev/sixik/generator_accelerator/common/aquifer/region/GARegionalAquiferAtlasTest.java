package dev.sixik.generator_accelerator.common.aquifer.region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GARegionalAquiferAtlasTest {
    static {
        System.setProperty("ga.aquifer.regionalAtlas.enabled", "true");
    }

    @AfterEach
    void tearDown() {
        GARegionalAquiferAtlas.clearForTests();
    }

    @Test
    void sampleAndGlobalLookupsDeduplicateWithinRegion() {
        GARegionalAquiferAtlasOwner owner = new GARegionalAquiferAtlasOwner(
                null,
                (x, y, z) -> null,
                new Object(),
                new Object(),
                new Object(),
                0,
                0,
                0,
                4,
                4
        );
        GARegionalAquiferAtlas.View view = GARegionalAquiferAtlas.view(owner, 0, 0);
        AtomicInteger sampleBuilds = new AtomicInteger();
        AtomicInteger globalBuilds = new AtomicInteger();

        GARegionalAquiferAtlas.Sample sampleA = view.samplePoint(12, 34, 18, () -> {
            sampleBuilds.incrementAndGet();
            return new GARegionalAquiferAtlas.Sample(40, (byte) 1, 7);
        });
        GARegionalAquiferAtlas.Sample sampleB = view.samplePoint(12, 34, 18, () -> {
            sampleBuilds.incrementAndGet();
            return new GARegionalAquiferAtlas.Sample(44, (byte) 2, 9);
        });

        GARegionalAquiferAtlas.Sample globalA = view.globalFluid(8, 20, 9, () -> {
            globalBuilds.incrementAndGet();
            return new GARegionalAquiferAtlas.Sample(30, (byte) 2, 11);
        });
        GARegionalAquiferAtlas.Sample globalB = view.globalFluid(8, 20, 9, () -> {
            globalBuilds.incrementAndGet();
            return new GARegionalAquiferAtlas.Sample(31, (byte) 3, 12);
        });

        assertEquals(1, sampleBuilds.get());
        assertEquals(1, globalBuilds.get());
        assertSame(sampleA, sampleB);
        assertSame(globalA, globalB);
        assertEquals((byte) 2, view.globalFluidKindAt(8, 10, 9, () -> globalA));
        assertEquals(30, view.globalFluidLevelAt(8, 20, 9, () -> globalA));
        assertEquals(11, view.globalFluidBlockIdAt(8, 20, 9, () -> globalA));
    }

    @Test
    void snapshotReportsAtlasCounters() {
        GARegionalAquiferAtlasOwner owner = new GARegionalAquiferAtlasOwner(
                null,
                (x, y, z) -> null,
                new Object(),
                new Object(),
                new Object(),
                0,
                0,
                0,
                4,
                4
        );
        GARegionalAquiferAtlas.view(owner, 0, 0).samplePoint(0, 0, 0,
                () -> new GARegionalAquiferAtlas.Sample(8, (byte) 1, 4));

        Map<String, Object> snapshot = GARegionalAquiferAtlas.snapshot();
        assertEquals(true, snapshot.get("enabled"));
        assertTrue(((Number) snapshot.get("regions")).intValue() >= 1);
        assertTrue(((Number) snapshot.get("sampleBuilds")).longValue() >= 1L);
    }
}
