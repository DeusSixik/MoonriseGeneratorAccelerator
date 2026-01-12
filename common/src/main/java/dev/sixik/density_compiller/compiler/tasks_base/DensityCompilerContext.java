package dev.sixik.density_compiller.compiler.tasks_base;

import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;

import java.util.function.Supplier;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.SIPUSH;

public record DensityCompilerContext(DensityCompiler compiler, MethodVisitor mv, String className, DensityFunction root) {

    public boolean canCompile(DensityFunction node) {
        return DensityCompilerData.getTask(node.getClass()) != null;
    }

    public void compileNode(DensityFunction node) {
        compileNode(mv, node);
    }

    public void compileNode(MethodVisitor mv, DensityFunction node) {
        final Class<? extends DensityFunction> clz = node.getClass();

        final Supplier<DensityCompilerTask<?>> taskSupplier = DensityCompilerData.getTask(clz);
        if (taskSupplier != null) {
            taskSupplier.get().compileComputeImpl(mv, node, this);
        } else {
            emitLeafCall(mv, node);
        }
    }

    public void emitLeafCall(DensityFunction leaf) {
        emitLeafCall(mv, leaf);
    }

    public void emitLeafCall(MethodVisitor mv, DensityFunction leaf) {
        int idx = compiler.leafToId.computeIfAbsent(leaf, k -> {
            compiler.leaves.add(k);
            return compiler.leaves.size() - 1;
        });

        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitFieldInsn(GETFIELD, className, "leaves", "[L" + DensityCompiler.INTERFACE_NAME + ";");

        if (idx <= 5) mv.visitInsn(ICONST_0 + idx);
        else if (idx <= 127) mv.visitIntInsn(BIPUSH, idx);
        else mv.visitIntInsn(SIPUSH, idx);

        mv.visitInsn(AALOAD); // Получили объект DensityFunction со стека

        mv.visitVarInsn(ALOAD, 1); // Загружаем Context (аргумент метода)

        // Вызываем compute
        mv.visitMethodInsn(INVOKEINTERFACE, DensityCompiler.INTERFACE_NAME, "compute", DensityCompiler.CONTEXT_DESC, true);
    }
}
