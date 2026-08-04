package dev.sixik.generator_accelerator.common.density.compiler.compiler.backend;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.HiddenClassLoader;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.plan.CompilationPlan;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class BytecodeCpuBackend implements DfcBackend {
    public static final BytecodeCpuBackend INSTANCE = new BytecodeCpuBackend();

    private BytecodeCpuBackend() {
    }

    @Override
    public String name() {
        return "bytecode-cpu";
    }

    @Override
    public boolean supports(CompilationPlan plan) {
        return true;
    }

    @Override
    public DfcBackendResult compile(CompilationPlan plan) {
        GlobalCompileCache.LookupResult lookup = GlobalCompileCache.INSTANCE.getOrCompile(
                plan.cacheFingerprint(),
                plan.exactFingerprint(),
                () -> emitAndLink(plan));
        return new DfcBackendResult(lookup.bundle(), lookup.reused());
    }

    private GlobalCompileCache.CopiedClassBundle emitAndLink(CompilationPlan plan) {
        Codegen.Result emitResult = Codegen.emit(
                plan.classInternalName(),
                plan.root(),
                plan.refs(),
                plan.extracted(),
                plan.pool(),
                plan.minValue(),
                plan.maxValue());
        byte[] bytecode = emitResult.bytecode();
        int helpersEmitted = emitResult.helpersEmitted();
        boolean latticeEmitted = emitResult.latticeEmitted();
        HiddenClassLoader.DefineResult defineResult = HiddenClassLoader.defineWithLookup(bytecode);
        Class<? extends CompiledDensityFunction> cls = defineResult.cls();
        MethodHandles.Lookup lookup = defineResult.lookup();

        MethodHandle[] helperHandles = resolveHelperHandles(plan.classInternalName(), cls, lookup, helpersEmitted);
        MethodHandle ctorMH = resolveConstructorHandle(plan.classInternalName(), cls, lookup);

        return new GlobalCompileCache.CopiedClassBundle(
                plan.classInternalName(),
                plan.sourceRootClass(),
                plan.rootDebug(),
                plan.splineDebug(),
                plan.exactFingerprint(),
                cls,
                bytecode,
                ctorMH,
                helperHandles,
                helpersEmitted,
                latticeEmitted,
                emitResult.cellAddLatticeSpecialized(),
                emitResult.cellAddBeardifierSpecialized(),
                emitResult.cellAddExternSpecialized(),
                emitResult.cellScalarMarkerSpecialized(),
                emitResult.cellScalarMarkerReason());
    }

    private static MethodHandle[] resolveHelperHandles(
            String classInternalName,
            Class<? extends CompiledDensityFunction> cls,
            MethodHandles.Lookup lookup,
            int helpersEmitted) {
        try {
            MethodHandle[] helperHandles = new MethodHandle[helpersEmitted];
            MethodType helperType = MethodType.methodType(
                    double.class,
                    CompiledDensityFunction.class,
                    DensityFunction.FunctionContext.class);
            for (int i = 0; i < helpersEmitted; i++) {
                helperHandles[i] = lookup.findStatic(cls, Codegen.helperName(i), helperType);
            }
            return helperHandles;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to resolve helper MethodHandles for "
                    + classInternalName + " (" + helpersEmitted + " helpers expected)", e);
        }
    }

    private static MethodHandle resolveConstructorHandle(
            String classInternalName,
            Class<? extends CompiledDensityFunction> cls,
            MethodHandles.Lookup lookup) {
        try {
            MethodType ctorType = MethodType.methodType(void.class,
                    double[].class, NormalNoise[].class, Object[].class, Object[].class,
                    DensityFunction[].class,
                    double.class, double.class,
                    MethodHandle[].class, MethodHandle.class);
            return lookup.findConstructor(cls, ctorType)
                    .asType(MethodType.methodType(CompiledDensityFunction.class,
                            double[].class, NormalNoise[].class, Object[].class, Object[].class,
                            DensityFunction[].class,
                            double.class, double.class,
                            MethodHandle[].class, MethodHandle.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to resolve constructor MethodHandle for "
                    + classInternalName, e);
        }
    }
}
