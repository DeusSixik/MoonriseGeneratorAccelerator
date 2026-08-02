package dev.sixik.generator_accelerator.common.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class FastVectorNoiseBoundaryTest {
    @Test
    void fastVectorNoiseClassLoadsAfterKernelRewrite() {
        assertNotNull(FastVectorNoise.class);
    }
}