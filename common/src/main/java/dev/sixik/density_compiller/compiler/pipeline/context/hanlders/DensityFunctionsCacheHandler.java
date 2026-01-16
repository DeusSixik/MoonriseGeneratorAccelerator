package dev.sixik.density_compiller.compiler.pipeline.context.hanlders;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContextHandler;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Set;

public interface DensityFunctionsCacheHandler extends PipelineAsmContextHandler {

    String BLENDER = "blender";
    String BLOCK_X = "blockX";
    String BLOCK_Y = "blockX";
    String BLOCK_Z = "blockX";

    int BLENDER_BITS = 1 << 1;
    int BLOCK_X_BITS = 1 << 2;
    int BLOCK_Y_BITS = 1 << 3;
    int BLOCK_Z_BITS = 1 << 4;

    default void putNeedCachedVariable(int bits) {
        String name = "";
        if((bits & BLENDER_BITS) != 0)
            name = BLENDER;
        else if((bits & BLOCK_X_BITS) != 0)
            name = BLOCK_X;
        else if((bits & BLOCK_Y_BITS) != 0)
            name = BLOCK_Y;
        else if((bits & BLOCK_Z_BITS) != 0)
            name = BLOCK_Z;

        if(name.isEmpty()) return;

        cache().needCachedVariables.add(name);
    }

    default void putNeedCachedVariable(String name) {
        cache().needCachedVariables.add(name);
    }

    default void putDensityToCache(DensityFunction function) {
        cache().cachedFunctions.put(function, cache().cachedFunctionsNumber++);
    }

    default int getDensityCache(DensityFunction function) {
        return cache().cachedFunctions.getOrDefault(function, -1);
    }

    default Set<String> getNeedCachedVariables() {
        return cache().needCachedVariables;
    }

    default void createNeedCache() {
        final var mv = ctx().mv();
        final var contextVar = ctx().getCurrentContextVar();

        final var list = getNeedCachedVariables();
        if(list.isEmpty()) return;

        if(contextVar == 1) {
            mv.visitVarInsn(ALOAD, 1);
        } else {
            mv.visitVarInsn(ALOAD, 2);
        }


        for (String ncv : list) {

            if(ncv.equals(BLENDER))  {
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
                int variable = ctx().createRefVarFromStack();
                ctx().putCachedVariable(BLENDER, variable);
            }

            if(ncv.equals(BLOCK_X)) {
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockX", "()I", true);
                int variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_X, variable);
            }

            if(ncv.equals(BLOCK_Y)) {

                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockY", "()I", true);
                int variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_Y, variable);
            }

            if(ncv.equals(BLOCK_Z)) {
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockZ", "()I", true);
                int variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_Z, variable);
            }

        }
    }
}
