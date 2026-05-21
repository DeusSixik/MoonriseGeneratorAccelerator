package dev.sixik.generator_accelerator.common.beardifier.region;

import dev.sixik.generator_accelerator.common.beardifier.GABeardifierPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GARegionalBeardifierAtlasTest {
    static {
        System.setProperty("ga.beardifier.regionalAtlas.enabled", "true");
    }

    @AfterEach
    void tearDown() {
        GARegionalBeardifierAtlas.clearForTests();
    }

    @Test
    void cellValuesDeduplicatePerRegionCell() {
        GABeardifierPlan plan = GABeardifierPlan.create(
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new int[0],
                new int[0],
                new int[0]
        );
        GARegionalBeardifierAtlasOwner owner = new GARegionalBeardifierAtlasOwner(plan, 4, 8);
        GARegionalBeardifierAtlas.View view = GARegionalBeardifierAtlas.view(owner, 0, 0);
        AtomicInteger builds = new AtomicInteger();

        double[] first = view.cellValues(0, 0, 0, () -> {
            builds.incrementAndGet();
            return new double[]{1.0D, 2.0D, 3.0D};
        });
        double[] second = view.cellValues(0, 0, 0, () -> {
            builds.incrementAndGet();
            return new double[]{9.0D};
        });

        assertEquals(1, builds.get());
        assertSame(first, second);
        assertArrayEquals(new double[]{1.0D, 2.0D, 3.0D}, second);
    }

    @Test
    void snapshotReportsAtlasStats() {
        GABeardifierPlan plan = GABeardifierPlan.create(
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new int[0],
                new int[0],
                new int[0]
        );
        GARegionalBeardifierAtlasOwner owner = new GARegionalBeardifierAtlasOwner(plan, 4, 8);
        GARegionalBeardifierAtlas.view(owner, 0, 0).cellValues(0, 0, 0, () -> new double[]{0.0D});

        Map<String, Object> snapshot = GARegionalBeardifierAtlas.snapshot();
        assertEquals(true, snapshot.get("enabled"));
        assertTrue(((Number) snapshot.get("cellBuilds")).longValue() >= 1L);
    }
}
