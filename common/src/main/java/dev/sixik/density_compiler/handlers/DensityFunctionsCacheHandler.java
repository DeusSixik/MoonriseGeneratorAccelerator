package dev.sixik.density_compiler.handlers;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Set;

public interface DensityFunctionsCacheHandler extends DCAsmHandler {

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

    Type CONTEXT_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;");
    Type BLENDER_TYPE = Type.getType("Lnet/minecraft/world/level/levelgen/blending/Blender;");

    default void putNeedCachedVariable(int... bits) {
        for (int bit : bits) {
            putNeedCachedVariable(bit);
        }
    }

    default void putNeedCachedVariable(int mask) {
        if ((mask & BLENDER_BITS) != 0) putNeedCachedVariable(BLENDER);
        if ((mask & BLOCK_X_BITS) != 0) putNeedCachedVariable(BLOCK_X);
        if ((mask & BLOCK_Y_BITS) != 0) putNeedCachedVariable(BLOCK_Y);
        if ((mask & BLOCK_Z_BITS) != 0) putNeedCachedVariable(BLOCK_Z);
        if ((mask & BLOCK_X_DIV_8_BITS) != 0) putNeedCachedVariable(BLOCK_X_DIV_8);
        if ((mask & BLOCK_Y_DIV_8_BITS) != 0) putNeedCachedVariable(BLOCK_Y_DIV_8);
        if ((mask & BLOCK_Z_DIV_8_BITS) != 0) putNeedCachedVariable(BLOCK_Z_DIV_8);
    }

    default void putNeedCachedVariable(String name) {
        dctx().needCachedVariables.add(name);
    }

    default void putDensityToCache(DensityFunction function) {
        dctx().cachedFunctions.put(function, dctx().cachedFunctionsNumber++);
    }

    default int getDensityCache(DensityFunction function) {
        return dctx().cachedFunctions.getOrDefault(function, -1);
    }

    default Set<String> getNeedCachedVariables() {
        return dctx().needCachedVariables;
    }

    default void createNeedCache() {
        final GeneratorAdapter ga = mv();
        final int contextVar = dctx().variableContextIndex;

        final Set<String> list = getNeedCachedVariables();
        if (list.isEmpty()) return;

        /*
            Cache Generation
         */
        for (String ncv : list) {
            if (ncv.equals(BLENDER)) {

                /*
                    cache().getCachedVariable(BLENDER) will return -1
                    if it hasn't been created yet.
                 */
                if (dctx().getCachedVariable(BLENDER) == -1) {
                    ga.loadLocal(contextVar);
                    ga.invokeInterface(CONTEXT_TYPE, Method.getMethod("net.minecraft.world.level.levelgen.blending.Blender getBlender()"));

                    int varIndex = ga.newLocal(BLENDER_TYPE);
                    ga.storeLocal(varIndex);
                    dctx().putCachedVariable(BLENDER, varIndex);
                }
            }

            /*
                Grouping coordinates, as the logic is similar
             */
            else if (ncv.equals(BLOCK_X) || ncv.equals(BLOCK_Y) || ncv.equals(BLOCK_Z)) {
                createCoordinateCache(ga, contextVar, ncv);
            }

            /*
                Dividing by 8 with lazy loading of the base coordinate
             */
            else if (ncv.equals(BLOCK_X_DIV_8)) {
                createDiv8Cache(ga, contextVar, BLOCK_X, BLOCK_X_DIV_8);
            }
            else if (ncv.equals(BLOCK_Y_DIV_8)) {
                createDiv8Cache(ga, contextVar, BLOCK_Y, BLOCK_Y_DIV_8);
            }
            else if (ncv.equals(BLOCK_Z_DIV_8)) {
                createDiv8Cache(ga, contextVar, BLOCK_Z, BLOCK_Z_DIV_8);
            }
        }
    }

    /**
     * Helper for creating a cache of coordinates (X, Y, Z)
     */
    private void createCoordinateCache(GeneratorAdapter ga, int contextVar, String name) {
        if (dctx().getCachedVariable(name) != -1) return; // Already exists

        ga.loadLocal(contextVar);
        /*
            The method name matches the variable name (blockX, blockY...)
         */
        ga.invokeInterface(CONTEXT_TYPE, new Method(name, Type.INT_TYPE, new Type[]{}));

        int varIndex = ga.newLocal(Type.INT_TYPE);
        ga.storeLocal(varIndex);
        dctx().putCachedVariable(name, varIndex);
    }

    /**
     * Helper for creating a div 8 cache
     */
    private void createDiv8Cache(GeneratorAdapter ga, int contextVar, String baseName, String divName) {
        /*
            Getting or creating a base coordinate (for example, BLOCK_X)
         */
        int baseVar = dctx().getCachedVariable(baseName);
        if (baseVar == -1) {
            createCoordinateCache(ga, contextVar, baseName);
            baseVar = dctx().getCachedVariable(baseName);
        }

        /*
            Counting divisions
         */
        ga.loadLocal(baseVar);
        ga.push(8);
        /*
            We use IDIV (for negative numbers it is important to keep the division sign)
         */
        ga.math(GeneratorAdapter.DIV, Type.INT_TYPE);

        int varIndex = ga.newLocal(Type.INT_TYPE);
        ga.storeLocal(varIndex);
        dctx().putCachedVariable(divName, varIndex);
    }
}
