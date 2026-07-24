package dev.sixik.generator_accelerator.common.density.compiler.compiler.plan;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuEligibility;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount;

import java.util.Set;

/**
 * Backend-neutral snapshot prepared after DFC frontend analysis and before backend emission.
 */
public record CompilationPlan(
        String sourceRootClass,
        IRNode root,
        RefCount.Result refs,
        ConstantPool pool,
        Set<IRNode> extracted,
        double minValue,
        double maxValue,
        int uniqueNodes,
        int cseSavings,
        int optimizerRewrites,
        int noisesSpecialized,
        int octavesUnrolled,
        SplineSearchStats splineStats,
        byte[] exactFingerprint,
        byte[] cacheFingerprint,
        String classInternalName,
        String rootDebug,
        String splineDebug,
        GpuEligibility.Report gpuEligibility,
        GpuPayloadCompiler.Result gpuPayload) {
}
