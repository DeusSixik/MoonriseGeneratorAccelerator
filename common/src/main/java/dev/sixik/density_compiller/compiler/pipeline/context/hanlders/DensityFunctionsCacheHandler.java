package dev.sixik.density_compiller.compiler.pipeline.context.hanlders;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContextHandler;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Set;

public interface DensityFunctionsCacheHandler extends PipelineAsmContextHandler {

    String BLENDER = "blender";
    String BLOCK_X = "blockX";
    String BLOCK_Y = "blockY";
    String BLOCK_Z = "blockZ";
    String BLOCK_X_DIV_8 = "blockX_div_8";
    String BLOCK_Y_DIV_8 = "blockY_div_8";
    String BLOCK_Z_DIV_8 = "blockZ_div_8";

    int BLENDER_BITS = 1 << 1;
    int BLOCK_X_BITS = 1 << 2;
    int BLOCK_Y_BITS = 1 << 3;
    int BLOCK_Z_BITS = 1 << 4;
    int BLOCK_X_DIV_8_BITS = 1 << 5;
    int BLOCK_Y_DIV_8_BITS = 1 << 6;
    int BLOCK_Z_DIV_8_BITS = 1 << 7;

    default void putNeedCachedVariable(int... bits) {
        for (int bit : bits) {
            putNeedCachedVariable(bit);
        }
    }

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
        else if((bits & BLOCK_X_DIV_8_BITS) != 0)
            name = BLOCK_X_DIV_8;
        else if((bits & BLOCK_Y_DIV_8_BITS) != 0)
            name = BLOCK_Y_DIV_8;
        else if((bits & BLOCK_Z_DIV_8_BITS) != 0)
            name = BLOCK_Z_DIV_8;

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

            int variable = -1;

            if(ncv.equals(BLENDER))  {
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
                variable = ctx().createRefVarFromStack();
                ctx().putCachedVariable(BLENDER, variable);
            }

            if(ncv.equals(BLOCK_X)) {
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockX", "()I", true);
                variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_X, variable);
            }

            if(ncv.equals(BLOCK_Y)) {

                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockY", "()I", true);
                variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_Y, variable);
            }

            if(ncv.equals(BLOCK_Z)) {
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockZ", "()I", true);
                variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_Z, variable);
            }

            if(ncv.equals(BLOCK_X_DIV_8)) {
                int varX = ctx().getCachedVariable(BLOCK_X);
                if(varX == -1) {
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockX", "()I", true);
                    varX = ctx().createIntVarFromStack();
                    ctx().putCachedVariable(BLOCK_X, varX);
                }

                ctx().readIntVar(varX);
                ctx().pushInt(8);
                ctx().mv().visitInsn(IDIV);
                variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_X_DIV_8, variable);
            }

            if(ncv.equals(BLOCK_Y_DIV_8)) {

                int varY = ctx().getCachedVariable(BLOCK_Y);
                if(varY == -1) {
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockY", "()I", true);
                    varY = ctx().createIntVarFromStack();
                    ctx().putCachedVariable(BLOCK_Y, varY);
                }

                ctx().readIntVar(varY);
                ctx().pushInt(8);
                ctx().mv().visitInsn(IDIV);
                variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_Y_DIV_8, variable);
            }

            if(ncv.equals(BLOCK_Z_DIV_8)) {
                int varZ = ctx().getCachedVariable(BLOCK_Z);
                if(varZ == -1) {
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext", "blockZ", "()I", true);
                    varZ = ctx().createIntVarFromStack();
                    ctx().putCachedVariable(BLOCK_Z, varZ);
                }

                ctx().readIntVar(varZ);
                ctx().pushInt(8);
                ctx().mv().visitInsn(IDIV);
                variable = ctx().createIntVarFromStack();
                ctx().putCachedVariable(BLOCK_Z_DIV_8, variable);
            }

            System.out.println("Create Cached variable: " + variable);
        }
    }
}
