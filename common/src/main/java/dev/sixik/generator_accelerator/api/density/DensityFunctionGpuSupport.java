package dev.sixik.generator_accelerator.api.density;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.DensityFunctionGpuKernelOp;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.DensityFunctionGpuKernelOpRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.DensityFunctionGpuPayloadBuilder;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.DensityFunctionGpuPayloadBuilderRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.DensityFunctionIrBuilder;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.DensityFunctionIrBuilderRegistry;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Public registration facade for adding GPU/IR support for custom density functions.
 *
 * <p>Compat modules should lower custom functions into the core DFC IR via these
 * builders. When the lowered graph uses GPU-payload-supported IR nodes, it becomes GPU
 * eligible without changing the shared kernel.</p>
 *
 * <p>For functions that cannot be cleanly represented in core IR but can be packed into
 * the primitive GPU payload instruction set, compat modules can register a payload
 * builder. Payload builders still use the shared kernel; they do not inject arbitrary
 * OpenCL source.</p>
 *
 * <p>Custom kernel ops are registered separately as source-fragment descriptors. They
 * are intentionally fail-closed with the current shared JavaToGpu kernel until a
 * generated-source backend is wired for DFC payloads.</p>
 */
public final class DensityFunctionGpuSupport {
    private DensityFunctionGpuSupport() {
    }

    public static <T extends DensityFunction> Registration registerIrBuilder(
            String id,
            Class<T> type,
            DensityFunctionIrBuilder<? super T> builder) {
        DensityFunctionIrBuilderRegistry.Registration registration =
                DensityFunctionIrBuilderRegistry.register(id, type, builder);
        return registration::unregister;
    }

    public static Registration registerIrBuilderByClassName(
            String id,
            String className,
            DensityFunctionIrBuilder<DensityFunction> builder) {
        DensityFunctionIrBuilderRegistry.Registration registration =
                DensityFunctionIrBuilderRegistry.registerByClassName(id, className, builder);
        return registration::unregister;
    }

    public static <T extends DensityFunction> Registration registerPayloadBuilder(
            String id,
            Class<T> type,
            DensityFunctionGpuPayloadBuilder<? super T> builder) {
        DensityFunctionGpuPayloadBuilderRegistry.Registration registration =
                DensityFunctionGpuPayloadBuilderRegistry.register(id, type, builder);
        return registration::unregister;
    }

    public static Registration registerPayloadBuilderByClassName(
            String id,
            String className,
            DensityFunctionGpuPayloadBuilder<DensityFunction> builder) {
        DensityFunctionGpuPayloadBuilderRegistry.Registration registration =
                DensityFunctionGpuPayloadBuilderRegistry.registerByClassName(id, className, builder);
        return registration::unregister;
    }

    public static Registration registerKernelOp(DensityFunctionGpuKernelOp op) {
        DensityFunctionGpuKernelOpRegistry.Registration registration =
                DensityFunctionGpuKernelOpRegistry.register(op);
        return registration::unregister;
    }

    @FunctionalInterface
    public interface Registration {
        void unregister();
    }
}
