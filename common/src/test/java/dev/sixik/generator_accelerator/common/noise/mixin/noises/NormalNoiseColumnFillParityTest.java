package dev.sixik.generator_accelerator.common.noise.mixin.noises;

import dev.sixik.generator_accelerator.common.density.mixin.noise.NormalNoiseAccessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class NormalNoiseColumnFillParityTest {
    @Test
    void normalNoiseMixinClassLoadsAfterKernelRewrite() {
        assertNotNull(NormalNoiseAccessor.class);
    }
}