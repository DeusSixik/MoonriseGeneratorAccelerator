package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Type;

public class DensityCompilerMarkerTask extends DensityCompilerTask<DensityFunctions.Marker> {
    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Marker node, Step step) {

        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        ctx.readNode(node.wrapped(), step);
        int id = ctx.mv().newLocal(Type.DOUBLE_TYPE);
        ctx.mv().dup2();
        ctx.mv().storeLocal(id);
        ctx.setVariable(node, id);
    }
}
