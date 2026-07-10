package dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode;

import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceCompilerConfig;
import dev.sixik.generator_accelerator.common.surface_compiler.cache.EpochClassLoader;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.interpreter.MaskInterpreterBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionContext;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceTier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;

/** Guarded Tier 1 backend gate for read-only adapter-assisted kernels. */
public final class HybridJitBackend {
    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    private final MaskInterpreterBackend interpreter = new MaskInterpreterBackend();

    public boolean canCompile(SurfaceExecutionPlan plan) {
        return SurfaceCompilerConfig.ENABLE_TIER1_HYBRID
                && plan != null
                && plan.tier() == SurfaceTier.GUARDED_HYBRID_JIT
                && plan.facts() != null
                && plan.facts().safeForHybrid()
                && this.interpreter.canExecute(plan);
    }

    public GeneratedKernel compile(SurfaceExecutionPlan plan) {
        if (!canCompile(plan)) {
            return null;
        }
        try {
            int classId = CLASS_COUNTER.incrementAndGet();
            byte[] bytecode = new HybridKernelEmitter(classId).emit();
            EpochClassLoader loader = EpochClassLoader.create(HybridJitBackend.class.getClassLoader());
            Class<?> generated = loader.define(this.getClass().getPackageName() + ".GeneratedHybridKernel" + classId, bytecode);
            Constructor<?> constructor = generated.getDeclaredConstructor(SurfaceExecutionPlan.class);
            constructor.setAccessible(true);
            return (GeneratedKernel) constructor.newInstance(plan);
        } catch (ReflectiveOperationException | LinkageError | IllegalArgumentException e) {
            return null;
        }
    }

    public boolean execute(GeneratedKernel kernel, SurfaceExecutionContext context) {
        return kernel != null && kernel.execute(context);
    }

    private static final class HybridKernelEmitter implements Opcodes {
        private static final String GENERATED_KERNEL = Type.getInternalName(GeneratedKernel.class);
        private static final String CONTEXT = Type.getInternalName(SurfaceExecutionContext.class);
        private static final String PLAN = Type.getInternalName(SurfaceExecutionPlan.class);
        private static final String SUPPORT = Type.getInternalName(HybridKernelSupport.class);
        private static final String OBJECT = Type.getInternalName(Object.class);

        private final String internalName;

        HybridKernelEmitter(int id) {
            this.internalName = "dev/sixik/generator_accelerator/common/surface_compiler/backend/bytecode/GeneratedHybridKernel" + id;
        }

        byte[] emit() {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            writer.visit(V21, ACC_PUBLIC | ACC_FINAL, this.internalName, null, OBJECT, new String[]{GENERATED_KERNEL});
            writer.visitField(ACC_PRIVATE | ACC_FINAL, "plan", "L" + PLAN + ";", null, null).visitEnd();
            emitConstructor(writer);
            emitExecute(writer);
            writer.visitEnd();
            return writer.toByteArray();
        }

        private void emitConstructor(ClassWriter writer) {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, "<init>", "(L" + PLAN + ";)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(PUTFIELD, this.internalName, "plan", "L" + PLAN + ";");
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void emitExecute(ClassWriter writer) {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, "execute", "(L" + CONTEXT + ";)Z", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, this.internalName, "plan", "L" + PLAN + ";");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "execute", "(L" + PLAN + ";L" + CONTEXT + ";)Z", false);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }
}
