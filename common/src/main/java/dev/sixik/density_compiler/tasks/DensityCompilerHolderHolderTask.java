package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;

public class DensityCompilerHolderHolderTask extends DensityCompilerTask<DensityFunctions.HolderHolder> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.HolderHolder node, Step step) {
        int id = ctx.getVariable(node);
        if(id != -1) {
            ctx.mv().loadLocal(id);
        } else {
            id = ctx.mv().newLocal(Type.getType(Holder.class));
            ctx.readNode(node.function().value(), step);
            ctx.mv().storeLocal(id);
            ctx.setVariable(node, id);
        }
    }
}
