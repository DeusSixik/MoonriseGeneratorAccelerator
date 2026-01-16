package dev.sixik.density_compiller.compiler.pipeline.context;

import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ContextCache {



    public int cachedLengthVar = -1;
    public int loopContextVar = -1;

    public int blenderVar = -1; // Кэш объекта Blender
    public int xVar = -1, yVar = -1, zVar = -1; // Кэши координат (double)

    public int cachedFunctionsNumber = 8000;

    public Set<String> needCachedVariables = new HashSet<>();

    public Map<DensityFunction, Integer> cachedFunctions = new Reference2ObjectOpenHashMap<>();
}
