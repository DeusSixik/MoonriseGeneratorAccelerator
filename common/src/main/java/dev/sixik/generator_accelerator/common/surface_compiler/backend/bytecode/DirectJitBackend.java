package dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode;

import dev.sixik.generator_accelerator.common.surface_compiler.cache.EpochClassLoader;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.template.ShapeTemplateBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceCompilerConfig;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceCommitMode;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionContext;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceTier;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier 0 backend gate. It refuses execution unless a caller provides an already
 * verified generated kernel and the plan is certified direct JIT.
 */
public final class DirectJitBackend {
    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();
    private final ShapeTemplateBackend templates = new ShapeTemplateBackend();

    public boolean canCompile(SurfaceExecutionPlan plan) {
        return SurfaceCompilerConfig.ENABLE_TIER0_DIRECT
                && plan != null
                && plan.tier() == SurfaceTier.CERTIFIED_DIRECT_JIT
                && plan.facts() != null
                && plan.facts().directWriteCertified()
                && plan.commitMode() == SurfaceCommitMode.DIRECT
                && this.templates.canUseDirectTemplate(plan.ir());
    }

    public GeneratedKernel compile(SurfaceExecutionPlan plan) {
        if (!canCompile(plan)) {
            return null;
        }
        BlockState constantState = constantState(plan);
        if (constantState == null) {
            return null;
        }
        try {
            int classId = CLASS_COUNTER.incrementAndGet();
            byte[] bytecode = new DirectKernelEmitter(classId).emit();
            EpochClassLoader loader = EpochClassLoader.create(DirectJitBackend.class.getClassLoader());
            Class<?> generated = loader.define(this.getClass().getPackageName() + ".GeneratedDirectKernel" + classId, bytecode);
            Constructor<?> constructor = generated.getDeclaredConstructor(BlockState.class);
            constructor.setAccessible(true);
            return (GeneratedKernel) constructor.newInstance(constantState);
        } catch (ReflectiveOperationException | LinkageError | IllegalArgumentException e) {
            return null;
        }
    }

    public boolean execute(GeneratedKernel kernel, SurfaceExecutionContext context) {
        return kernel != null && kernel.execute(context);
    }

    private static BlockState constantState(SurfaceExecutionPlan plan) {
        return plan == null ? null : new ShapeTemplateBackend().directConstantState(plan.ir());
    }

    private static final class DirectKernelEmitter implements Opcodes {
        private static final String GENERATED_KERNEL = Type.getInternalName(GeneratedKernel.class);
        private static final String CONTEXT = Type.getInternalName(SurfaceExecutionContext.class);
        private static final String BLOCK_STATE = Type.getInternalName(BlockState.class);
        private static final String DIRECT_WRITES = Type.getInternalName(DirectWriteSupport.class);
        private static final String OBJECT = Type.getInternalName(Object.class);

        private final String internalName;

        DirectKernelEmitter(int id) {
            this.internalName = "dev/sixik/generator_accelerator/common/surface_compiler/backend/bytecode/GeneratedDirectKernel" + id;
        }

        byte[] emit() {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            writer.visit(V21, ACC_PUBLIC | ACC_FINAL, this.internalName, null, OBJECT, new String[]{GENERATED_KERNEL});
            writer.visitField(ACC_PRIVATE | ACC_FINAL, "state", "L" + BLOCK_STATE + ";", null, null).visitEnd();
            emitConstructor(writer);
            emitExecute(writer);
            writer.visitEnd();
            return writer.toByteArray();
        }

        private void emitConstructor(ClassWriter writer) {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, "<init>", "(L" + BLOCK_STATE + ";)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(PUTFIELD, this.internalName, "state", "L" + BLOCK_STATE + ";");
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void emitExecute(ClassWriter writer) {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, "execute", "(L" + CONTEXT + ";)Z", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, this.internalName, "state", "L" + BLOCK_STATE + ";");
            mv.visitMethodInsn(INVOKESTATIC, DIRECT_WRITES, "fillChunkDirect", "(L" + CONTEXT + ";L" + BLOCK_STATE + ";)Z", false);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }
}
