package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.CompilationFingerprint;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CodegenSplineBindingTest {

    @Test
    void nestedBinarySplineBindingsAreStableBeforeFingerprintAndAcrossEmission() {
        IRNode root = new IRNode.Bin(
                IRNode.BinOp.ADD,
                new IRNode.YClampedGradient(-64, 320, 1.5, -1.5),
                fivePointSpline());
        ConstantPool definingPool = new ConstantPool();
        ConstantPool cacheHitPool = new ConstantPool();

        assertEquals(0, definingPool.splineCount());
        assertEquals(0, cacheHitPool.splineCount());

        Codegen.prepareRuntimeBindings(root, definingPool);
        Codegen.prepareRuntimeBindings(root, cacheHitPool);

        assertEquals(1, definingPool.splineCount());
        assertEquals(1, cacheHitPool.splineCount());
        assertArrayEquals(
                CompilationFingerprint.shapeSha256(root, definingPool, -1.0, 1.0),
                CompilationFingerprint.shapeSha256(root, cacheHitPool, -1.0, 1.0));

        int preparedCount = definingPool.splineCount();
        Codegen.emit(
                "dev/sixik/generator_accelerator/common/density/compiler/compiler/codegen/CompiledDF_SplineBindingTest",
                root,
                RefCount.compute(root),
                Set.of(),
                definingPool,
                -1.0,
                1.0);

        assertEquals(preparedCount, definingPool.splineCount());
        assertEquals(preparedCount, cacheHitPool.finishSplines().length);
    }

    private static IRNode fivePointSpline() {
        return new IRNode.Spline.Multipoint(
                new IRNode.BlockX(),
                new float[]{-1.0F, -0.5F, 0.0F, 0.5F, 1.0F},
                List.of(
                        new IRNode.Spline.Constant(-1.0F),
                        new IRNode.Spline.Constant(-0.5F),
                        new IRNode.Spline.Constant(0.0F),
                        new IRNode.Spline.Constant(0.5F),
                        new IRNode.Spline.Constant(1.0F)),
                new float[]{0.0F, 0.0F, 0.0F, 0.0F, 0.0F},
                -1.0F,
                1.0F);
    }
}
