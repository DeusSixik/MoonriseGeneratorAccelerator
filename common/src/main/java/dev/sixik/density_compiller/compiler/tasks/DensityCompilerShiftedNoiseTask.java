package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.PublicNoiseWrapper;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftedNoiseTask extends DensityCompilerTask<DensityFunctions.ShiftedNoise> {

    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String WRAPPER = Type.getInternalName(PublicNoiseWrapper.class);

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.ShiftedNoise node, DensityCompilerContext ctx) {

        // --- ШАГ 0: Подготовка объекта NoiseHolder ---
        // СТЕК ДО: []

        // 1. Создаем обертку и регистрируем её как лист
        PublicNoiseWrapper wrapper = new PublicNoiseWrapper(node.noise());
        ctx.emitLeafCallReference(mv, wrapper);
        // Стек: [WrapperObject]

        // 2. Превращаем обертку в сам NoiseHolder (вызываем метод .holder())
        mv.visitTypeInsn(CHECKCAST, WRAPPER);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        // СТЕК ПОСЛЕ: [NoiseHolder]

        // --- ШАГ 1: Вычисляем X ---
        // СТЕК ДО: [NoiseHolder]

        // 1. Берем blockX из контекста
        mv.visitVarInsn(ALOAD, 1); // Грузим Context
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "blockX", "()I", true);
        mv.visitInsn(I2D); // Превращаем int в double
        // Стек: [NoiseHolder, blockX]

        // 2. Умножаем на xzScale
        mv.visitLdcInsn(node.xzScale());
        mv.visitInsn(DMUL);
        // Стек: [NoiseHolder, blockX * scale]

        // 3. Вычисляем смещение shiftX (Инлайним код ребенка!)
        // Это добавит свой результат на верхушку стека
        ctx.compileNode(mv, node.shiftX());
        // Стек: [NoiseHolder, scaledBlockX, shiftResult]

        // 4. Складываем
        mv.visitInsn(DADD);

        // СТЕК ПОСЛЕ: [NoiseHolder, FinalX]

        // --- ШАГ 2: Вычисляем Y ---
        // СТЕК ДО: [NoiseHolder, FinalX]

        // 1. Берем blockY
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "blockY", "()I", true);
        mv.visitInsn(I2D);

        // 2. Умножаем на yScale
        mv.visitLdcInsn(node.yScale());
        mv.visitInsn(DMUL);

        // 3. Вычисляем shiftY
        ctx.compileNode(mv, node.shiftY());

        // 4. Складываем
        mv.visitInsn(DADD);

        // СТЕК ПОСЛЕ: [NoiseHolder, FinalX, FinalY]

        // --- ШАГ 3: Вычисляем Z ---
        // СТЕК ДО: [NoiseHolder, FinalX, FinalY]

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "blockZ", "()I", true);
        mv.visitInsn(I2D);

        mv.visitLdcInsn(node.xzScale());
        mv.visitInsn(DMUL);

        ctx.compileNode(mv, node.shiftZ());

        mv.visitInsn(DADD);

        // СТЕК ПОСЛЕ: [NoiseHolder, FinalX, FinalY, FinalZ]
        // --- ШАГ 4: Вызываем getValue ---
        // Стек готов: Объект, X, Y, Z. Можно вызывать!

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);

        // СТЕК ПОСЛЕ: [ResultDouble]
        // ASM в DensityCompiler сам добавит DRETURN в конце
    }
}
