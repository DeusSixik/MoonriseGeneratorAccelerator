package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuEligibilityTest {

    @Test
    void inlinedNoiseIsPrimitivePayloadEligible() {
        IRNode root = new IRNode.InlinedNoise(
                0,
                IRNode.BlockX.INSTANCE,
                IRNode.BlockY.INSTANCE,
                IRNode.BlockZ.INSTANCE,
                1.0D);

        GpuEligibility.Report report = GpuEligibility.analyze(root, null);

        assertTrue(report.eligible());
        assertEquals(0, report.blockerCount());
        assertEquals("none", report.firstBlocker());
    }

    @Test
    void rawVanillaNoiseObjectStillBlocksGpuPayload() {
        IRNode root = new IRNode.Noise(0, 1.0D, 1.0D, 1.0D);

        GpuEligibility.Report report = GpuEligibility.analyze(root, null);

        assertFalse(report.eligible());
        assertEquals(1, report.blockerCount());
        assertEquals("VANILLA_NOISE_OBJECT", report.firstBlocker());
    }
}
