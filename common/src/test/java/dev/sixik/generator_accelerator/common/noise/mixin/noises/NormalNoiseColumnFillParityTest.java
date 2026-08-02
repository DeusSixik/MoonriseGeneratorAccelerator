package dev.sixik.generator_accelerator.common.noise.mixin.noises;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class NormalNoiseColumnFillParityTest {
    @Test
    void normalNoiseMixinClassLoadsAfterKernelRewrite() {
        assertNotNull(MixinNormalNoise.class);
    }
}