package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;

public class DensityCompilerClampTask extends DensityCompilerTask<DensityFunctions.Clamp> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Clamp node, Step step) {

        if(step != Step.Compute) {
            ctx.readNode(node.input(), step);
            return;
        }

        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        DensityFunction input = node.input();
        double cMin = node.minValue(); // Границы клэмпа
        double cMax = node.maxValue();

        double inMin = input.minValue(); // Границы входной функции
        double inMax = input.maxValue();

        // 1. Оптимизация: Константный результат
        // Если вход всегда меньше минимума -> результат всегда min
        if (inMax <= cMin) {
            ctx.push(cMin);
            return;
        }
        // Если вход всегда больше максимума -> результат всегда max
        if (inMin >= cMax) {
            ctx.push(cMax);
            return;
        }

        // 2. Оптимизация: Clamp не нужен (вход уже внутри границ)
        if (inMin >= cMin && inMax <= cMax) {
            ctx.readNode(input, Step.Compute);
            return;
        }

        ctx.readNode(input, Step.Compute);

        boolean needMaxCheck = inMax > cMax;
        boolean needMinCheck = inMin < cMin;

        if (needMaxCheck) {
            ctx.push(cMax);
            ctx.invokeMethodStatic(Math.class, "min", "(DD)D");
        }

        if (needMinCheck) {
            ctx.push(cMin);
            ctx.invokeMethodStatic(Math.class, "max", "(DD)D");
        }

        int id = ctx.mv().newLocal(Type.DOUBLE_TYPE);
        ctx.mv().dup2();
        ctx.mv().storeLocal(id);
        ctx.setVariable(node, id);
    }
}
