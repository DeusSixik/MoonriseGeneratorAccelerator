package dev.sixik.generator_accelerator.common.beardifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GABeardifierHotPathTest {

    @Test
    void optimizedScalarMatchesLegacyAcrossSamplePoints() {
        GABeardifierHotPathHarness.Scenario scenario = GABeardifierHotPathHarness.createScenario(48, 16, 12345L);
        int[][] points = {
                {-8, 32, -8},
                {0, 40, 0},
                {12, 56, -4},
                {19, 64, 17},
                {-23, 71, 9}
        };

        for (int[] point : points) {
            assertEquals(
                    GABeardifierHotPathHarness.legacyScalarAt(scenario, point[0], point[1], point[2]),
                    GABeardifierHotPathHarness.optimizedScalarAt(scenario, point[0], point[1], point[2]),
                    "point=" + point[0] + "," + point[1] + "," + point[2]
            );
        }
    }

    @Test
    void singlePassScalarMatchesLegacyAcrossDenseSamplePoints() {
        GABeardifierHotPathHarness.Scenario scenario = GABeardifierHotPathHarness.createScenario(48, 16, 54321L);

        for (int x = -24; x <= 24; x += 8) {
            for (int y = 24; y <= 88; y += 8) {
                for (int z = -24; z <= 24; z += 8) {
                    assertEquals(
                            GABeardifierHotPathHarness.legacyScalarAt(scenario, x, y, z),
                            GABeardifierHotPathHarness.singlePassScalarAt(scenario, x, y, z),
                            "point=" + x + "," + y + "," + z
                    );
                }
            }
        }
    }

    @Test
    void optimizedCellMatchesLegacyOutputs() {
        GABeardifierHotPathHarness.Scenario scenario = GABeardifierHotPathHarness.createScenario(64, 20, 987654321L);
        double[] legacy = GABeardifierHotPathHarness.fillCellLegacy(scenario, -16, 48, 12, 4, 8);
        double[] optimized = GABeardifierHotPathHarness.fillCellOptimized(scenario, -16, 48, 12, 4, 8);

        assertArrayEquals(legacy, optimized);
    }

    @Test
    void printsBeardifierHotPathMetrics() {
        int warmup = Integer.getInteger("ga.test.beardifierWarmup", 2_000);
        int iterations = Integer.getInteger("ga.test.beardifierIterations", 10_000);
        GABeardifierHotPathHarness.Scenario scenario = GABeardifierHotPathHarness.createScenario(72, 24, 24680L);

        long legacyScalar = GABeardifierHotPathHarness.benchmarkLegacyScalar(scenario, warmup, iterations);
        long optimizedScalar = GABeardifierHotPathHarness.benchmarkOptimizedScalar(scenario, warmup, iterations);
        long legacyCell = GABeardifierHotPathHarness.benchmarkLegacyCell(scenario, warmup, iterations);
        long optimizedCell = GABeardifierHotPathHarness.benchmarkOptimizedCell(scenario, warmup, iterations);

        System.out.println("Beardifier hot path benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", pieces=72, junctions=24");
        GABeardifierHotPathHarness.printMetric("scalar", legacyScalar, optimizedScalar, iterations);
        GABeardifierHotPathHarness.printMetric("cell", legacyCell, optimizedCell, iterations);
    }
}
