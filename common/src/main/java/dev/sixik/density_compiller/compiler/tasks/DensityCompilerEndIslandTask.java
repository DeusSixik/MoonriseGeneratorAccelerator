package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;


public class DensityCompilerEndIslandTask extends DensityCompilerTask<DensityFunctions.EndIslandDensityFunction> {
    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.EndIslandDensityFunction node, DensityCompilerContext ctx) {
        ctx.emitLeafCallReference(mv, node);
        // Стек: [DensityFunction]

        // 2. !!! ФИКС: Явное приведение типа !!!
        // В ASM внутренние классы разделяются '$', а пакеты '/'
        mv.visitTypeInsn(CHECKCAST, "net/minecraft/world/level/levelgen/DensityFunctions$EndIslandDensityFunction");
        // Стек теперь (для верификатора): [EndIslandDensityFunction]

        // 3. Загружаем Context
        mv.visitVarInsn(ALOAD, 1);
        // Стек: [EndIslandDensityFunction, FunctionContext]

        // 4. Вызываем статический хелпер
        mv.visitMethodInsn(INVOKESTATIC,
                "dev/sixik/density_compiller/compiler/wrappers/EndIslandHelper", // Твой путь к хелперу
                "fastCompute",
                "(Lnet/minecraft/world/level/levelgen/DensityFunctions$EndIslandDensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
                false);
    }
}
