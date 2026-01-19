package dev.sixik.density_compiler;

import dev.sixik.asm.BasicAsmContext;
import dev.sixik.asm.utils.DescriptorBuilder;
import dev.sixik.density_compiler.data.DensityCompilerData;
import dev.sixik.density_compiler.data.DensityCompilerLocals;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.function.Function;
import java.util.function.Supplier;

public class DCAsmContext extends BasicAsmContext {

    public static final Function<Object, String> DEFAULT_LEAF_FUNCTION_NAME = (leaf) -> leaf.getClass().getSimpleName();

    public final DensityCompiler compiler;
    public final int variableContextIndex;

    public DCAsmContext(
            DensityCompiler compiler,
            GeneratorAdapter mv,
            int variableIndexOffset,
            int variableContextIndex
    ) {
        super(mv, variableIndexOffset);
        this.compiler = compiler;
        this.variableContextIndex = variableContextIndex;
    }

    public void getField(String fieldName, String fieldDescriptor) {
        mv.visitFieldInsn(GETFIELD, compiler.configuration.className(), fieldName, fieldDescriptor);
    }

    public void readNode(DensityFunction node, DensityCompilerTask.Step step) {
        try {
            final Class<? extends DensityFunction> clz = node.getClass();
            final Supplier<DensityCompilerTask<?>> taskSupplier = DensityCompilerData.getTask(clz);

            if (taskSupplier != null) {
                taskSupplier.get().applyStepImpl(this, node, step);
                return;
            }

            invokeLeafCompute(node);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void readLeaf(DensityFunction leaf) {
        final int variable = getOrCreateLeafIndex(leaf);

        String fieldName = DEFAULT_LEAF_FUNCTION_NAME.apply(leaf) + "_" + variable;
        String fieldDescriptor = DescriptorBuilder.builder().type(DensityFunction.class).build();

        mv.loadThis();
        getField(fieldName, fieldDescriptor);
    }

    public void invokeLeafCompute(DensityFunction leaf) {
        readLeaf(leaf);
        readContext();
        invokeMethodInterface(
                DensityFunction.class,
                "compute",
                DescriptorBuilder.builder().type(DensityFunction.FunctionContext.class).buildMethod(double.class)
        );
    }

    public void readContext() {
        if (variableContextIndex == -1)
            throw new IllegalStateException("Context no exist on this visitor!");
        mv.visitVarInsn(ALOAD, variableContextIndex);
    }

    protected int getOrCreateLeafIndex(DensityFunction leaf) {
        final DensityCompilerLocals locals = compiler.locals;
        return locals.leafToId.computeIfAbsent(leaf, (k) -> {
            locals.leaves.add(k);
            return locals.leaves.size() - 1;
        });
    }


}
