package dev.sixik.density_compiller.compiler.pipeline.context;

public class ContextCache {

    public int cachedLengthVar = -1;
    public int loopContextVar = -1;

    public int blenderVar = -1; // Кэш объекта Blender
    public int xVar = -1, yVar = -1, zVar = -1; // Кэши координат (double)
}
