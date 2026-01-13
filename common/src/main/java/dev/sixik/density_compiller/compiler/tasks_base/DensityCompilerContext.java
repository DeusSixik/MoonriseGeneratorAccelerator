package dev.sixik.density_compiller.compiler.tasks_base;

import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.DensityCompilerParams;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.function.Supplier;

import static org.objectweb.asm.Opcodes.*;

public record DensityCompilerContext(DensityCompiler compiler, MethodVisitor mv, String className, DensityFunction root) {

    private static final String DENSITY_FUNCTION_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction";

    public String CTX() {
        return DensityCompiler.CTX;
    }

    public boolean canCompile(DensityFunction node) {
        return DensityCompilerData.getTask(node.getClass()) != null;
    }

    public void compileNode(DensityFunction node) {
        compileNode(mv, node);
    }

    public void compileNode(MethodVisitor mv, DensityFunction node) {
        String nodeName = node.getClass().getSimpleName();
        DensityCompiler.L_LINK.get().push(nodeName); // Recording the entrance to the node

        try {
            final Class<? extends DensityFunction> clz = node.getClass();
            final Supplier<DensityCompilerTask<?>> taskSupplier = DensityCompilerData.getTask(clz);

            if (taskSupplier != null) {
                taskSupplier.get().compileComputeImpl(mv, node, this);
            } else {
                if (DensityCompilerParams.crashIfUnsupportedType) {

                    /*
                        If we fall here, we'll see the path in the logs above.
                     */
                    printTrace("Unsupported Type: " + node.getClass().getName());
                    throw new UnsupportedOperationException("Un support for class: " + node.getClass().getName());
                }
                emitLeafCall(mv, node);
            }
        } catch (Exception e) {

            /*
                If any error has occurred (including ASM or NPE)
             */
            printTrace("Error while compiling node");
            throw e;
        }
    }

    private void printTrace(String message) {
        System.err.println("[DensityCompiler Trace] " + message);
        System.err.println("Compilation Path (top is current):");
        int depth = 0;
        for (String s : DensityCompiler.L_LINK.get()) {
            System.err.println("  " + (depth++) + ": " + s);
        }
    }

    public void emitLeafCall(DensityFunction leaf) {
        emitLeafCall(mv, leaf);
    }

    public void emitLeafCall(MethodVisitor mv, DensityFunction leaf) {
        // 1. Сначала загружаем поле с объектом (используем нашу новую логику полей)
        emitLeafCallReference(mv, leaf);
        // Стек: [ConcreteClassType]

        // 2. Поскольку поле имеет тип конкретного класса (например, SomeModFunction),
        // а нам нужно вызвать метод интерфейса DensityFunction.compute,
        // безопаснее всего скастить его к интерфейсу (хотя для invokevirtual это необязательно,
        // но для invokeinterface нужно правильное имя владельца).
        mv.visitTypeInsn(CHECKCAST, DENSITY_FUNCTION_INTERNAL);

        // 3. Загружаем аргумент Context
        mv.visitVarInsn(ALOAD, 1);

        // 4. Вызываем compute через интерфейс DensityFunction
        // Мы НЕ можем использовать DensityCompiler.INTERFACE_NAME, так как это Object
        mv.visitMethodInsn(INVOKEINTERFACE,
                DENSITY_FUNCTION_INTERNAL,
                "compute",
                DensityCompiler.CONTEXT_DESC,
                true);
    }

    public void emitLeafCallReference(MethodVisitor mv, Object leaf) {
//        int idx = compiler.leafToId.computeIfAbsent(leaf, k -> {
//            compiler.leaves.add(k);
//            return compiler.leaves.size() - 1;
//        });
//
//        mv.visitVarInsn(ALOAD, 0); // this
//        mv.visitFieldInsn(GETFIELD, className, "leaves", "[L" + DensityCompiler.INTERFACE_NAME + ";");
//
//        if (idx <= 5) mv.visitInsn(ICONST_0 + idx);
//        else if (idx <= 127) mv.visitIntInsn(BIPUSH, idx);
//        else mv.visitIntInsn(SIPUSH, idx);
//
//        mv.visitInsn(AALOAD);

        // 1. Регистрируем лист и получаем ID
        int idx = compiler.leafToId.computeIfAbsent(leaf, k -> {
            compiler.leaves.add(k);
            return compiler.leaves.size() - 1;
        });

        // 2. Получаем дескриптор типа для этого конкретного объекта
        String descriptor = Type.getDescriptor(leaf.getClass());

        // 3. Генерируем прямой доступ к полю
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitFieldInsn(GETFIELD, className, "leaf_" + idx, descriptor);
    }
}
