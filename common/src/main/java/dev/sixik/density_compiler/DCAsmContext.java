package dev.sixik.density_compiler;

import dev.sixik.asm.BasicAsmContext;
import dev.sixik.asm.utils.DescriptorBuilder;
import dev.sixik.density_compiler.data.DensityCompilerData;
import dev.sixik.density_compiler.data.DensityCompilerLocals;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class DCAsmContext extends BasicAsmContext {

    public static final Function<Object, String> DEFAULT_LEAF_FUNCTION_NAME = (leaf) -> leaf.getClass().getSimpleName();

    public final DensityCompiler compiler;
    public final int variableContextIndex;

    public int arrayLengthVar = -1;
    public int arrayFillVar = -1;
    public boolean needCachedForIndex = false;
    public int arrayForIndexVar = -1;

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
                compiler.stackMachine.pushStack(node.getClass().getSimpleName() + " | " + step.name());

                taskSupplier.get().applyStepImpl(this, node, step);

                compiler.stackMachine.popStack();
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
        if (variableContextIndex == -1) {
            compiler.stackMachine.printDebug();
            throw new IllegalStateException("Context no exist on this visitor!");
        }

        if(arrayForIndexVar != -1) {
            mv.loadLocal(arrayForIndexVar);
        } else {
            mv.visitVarInsn(ALOAD, variableContextIndex);
        }
    }

    protected int getOrCreateLeafIndex(DensityFunction leaf) {
        final DensityCompilerLocals locals = compiler.locals;
        return locals.leafToId.computeIfAbsent(leaf, (k) -> {
            locals.leaves.add(k);
            return locals.leaves.size() - 1;
        });
    }

    public void push(double value) {
        mv.push(value);
    }

    public void push(int value) {
        mv.push(value);
    }

    public void push(boolean value) {
        mv.push(value);
    }

    public int getOrComputeLength(int destArrayArgIndex) {
        int len = this.arrayLengthVar;

        if (len == -1) {
            GeneratorAdapter ga = mv();
            len = ga.newLocal(Type.INT_TYPE);

            ga.loadArg(destArrayArgIndex);

            ga.arrayLength();
            ga.storeLocal(len);

            this.arrayLengthVar = len;
        }
        return len;
    }

    public void arrayForI(int destArrayVar, Consumer<Integer> iteration) {
        /*
            We get the same length variable for the entire method.
         */
        int lenVar = getOrComputeLength(destArrayVar);

        /*
            The i counter must still be unique for each loop
            so that there are no nesting conflicts, but the JIT often collapses them on its own.
         */
        int iVar = mv.newLocal(Type.INT_TYPE);

        // int i = 0;
        mv.visitInsn(ICONST_0);
        mv.storeLocal(iVar);

        Label startLoop = new Label();
        Label endLoop = new Label();

        mv.visitLabel(startLoop);

        // if (i >= len) break
        mv.loadLocal(iVar);
        mv.loadLocal(lenVar);
        mv.visitJumpInsn(IF_ICMPGE, endLoop);

        iteration.accept(iVar);

        mv.iinc(iVar, 1);

        mv.visitJumpInsn(GOTO, startLoop);

        mv.visitLabel(endLoop);
    }
}
