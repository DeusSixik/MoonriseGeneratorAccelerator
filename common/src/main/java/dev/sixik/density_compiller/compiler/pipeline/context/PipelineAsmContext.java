package dev.sixik.density_compiller.compiler.pipeline.context;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.locals.DensityCompilerLocals;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.objectweb.asm.Opcodes.*;

public class PipelineAsmContext extends AsmCtx {

    public static final String DEFAULT_LEAF_FUNCTION_NAME = "leaf_function";

    protected final DensityCompilerPipeline pipeline;
    protected final ContextCache cache = new ContextCache();

    public PipelineAsmContext(
            DensityCompilerPipeline pipeline,
            MethodVisitor mv,
            String ownerInternalName,
            int firstFreeLocal,
            int currentContextVar
    ) {
        super(mv, ownerInternalName, firstFreeLocal, currentContextVar);
        this.pipeline = pipeline;
    }

    public void putField(int iVar) {
        mv.visitFieldInsn(PUTFIELD,
                pipeline.configurator.className(),
                DEFAULT_LEAF_FUNCTION_NAME + "_" + iVar,
                DescriptorBuilder.builder().type(DensityFunction.class).build());
    }

    public int getOrCreateLeafIndex(DensityFunction leaf) {
        final DensityCompilerLocals locals = pipeline.locals;
        return locals.leafToId.computeIfAbsent(leaf, (k) -> {
            locals.leaves.add(k);
            return locals.leaves.size() - 1;
        });
    }

    public int getOrComputeLength(int destArrayVar) {
        final ContextCache cache = this.cache;
        int len = cache.cachedLengthVar;

        if (len == -1) {
            len = newLocalInt();
            aload(destArrayVar);
            mv.visitInsn(ARRAYLENGTH);
            istore(len);
        }
        return len;
    }

    public void visitLeaf(DensityFunction leaf) {
        final int variable = getOrCreateLeafIndex(leaf);

        String fieldName = DEFAULT_LEAF_FUNCTION_NAME + "_" + variable;
        String fieldDescriptor = DescriptorBuilder.builder().type(DensityFunction.class).build();

        loadThis();
        getField(fieldName, fieldDescriptor);
    }

    public void visitNodeCompute(DensityFunction node) {
        try {
            final Class<? extends DensityFunction> clz = node.getClass();
            final Supplier<DensityCompilerTask<?>> taskSupplier = DensityCompilerData.getTask(clz);

            if (taskSupplier != null) {
                final DensityCompilerTask<?> task = taskSupplier.get();
                if((task.buildBits() & DensityCompilerTask.COMPUTE) != 0) {
                    taskSupplier.get().compileComputeImpl(mv, node, this);
                    return;
                }
            }

            visitLeafCall(node);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void visitLeafCall(DensityFunction node) {
        visitLeaf(node);
        visitContext();
        invokeInterface(DensityCompiler.INTERFACE_NAME, "compute", DensityCompiler.CONTEXT_DESC);
    }

    public void visitContext() {
        mv.visitVarInsn(ALOAD, currentContextVar);
    }

    public void arrayForFill(
            int destArrayVar,
            double value
    ) {
        arrayForI(destArrayVar, (i) -> {
            final MethodVisitor mv = mv();
            mv.visitVarInsn(ALOAD, destArrayVar);   // Array
            mv.visitVarInsn(ILOAD, i);              // Index
            mv.visitLdcInsn(value);                 // Value
            mv.visitInsn(DASTORE);                  // Store double
        });
    }

    public void arrayForI(
            int destArrayVar,
            Consumer<Integer> iteration
    ) {
        /*
            We get the same length variable for the entire method.
         */
        int lenVar = getOrComputeLength(destArrayVar);

        /*
            The i counter must still be unique for each loop
            so that there are no nesting conflicts, but the JIT often collapses them on its own.
         */
        int iVar = newLocalInt();

        final MethodVisitor mv = mv();

        // int i = 0;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, iVar);

        Label startLoop = new Label();
        Label endLoop = new Label();

        mv.visitLabel(startLoop);

        // if (i >= len) break
        mv.visitVarInsn(ILOAD, iVar);
        mv.visitVarInsn(ILOAD, lenVar); // Using the general lenVar
        mv.visitJumpInsn(IF_ICMPGE, endLoop);

        iteration.accept(iVar);

        mv.visitIincInsn(iVar, 1);
        mv.visitJumpInsn(GOTO, startLoop);

        mv.visitLabel(endLoop);
    }

}
