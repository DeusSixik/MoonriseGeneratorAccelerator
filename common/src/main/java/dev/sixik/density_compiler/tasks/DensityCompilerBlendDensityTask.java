package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.handlers.DensityFunctionsCacheHandler;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

public class DensityCompilerBlendDensityTask extends DensityCompilerTask<DensityFunctions.BlendDensity> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.BlendDensity node, Step step) {


        if(step == Step.Prepare) {
            ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLENDER_BITS);
            ctx.needCachedForIndex = true;
        }

        if(step != Step.Compute) {
            ctx.readNode(node.input(), step);
            return;
        }

        GeneratorAdapter ga = ctx.mv();

        int blenderVar = ctx.getCachedVariable(DensityFunctionsCacheHandler.BLENDER);

        // Типы и Методы для ASM Commons
        Type blenderType = Type.getType("Lnet/minecraft/world/level/levelgen/blending/Blender;");

        // 1. Грузим 'this' (объект Blender)
        ga.loadLocal(blenderVar);

        // 2. Грузим 1-й аргумент (FunctionContext)
        ctx.readContext();

        // 3. Грузим 2-й аргумент (Результат вычисления input)
        ctx.readNode(node.input(), Step.Compute);

        // 4. Вызываем метод
        ga.invokeVirtual(
                blenderType,
                Method.getMethod("double blendDensity(net.minecraft.world.level.levelgen.DensityFunction$FunctionContext, double)")
        );
    }
}
