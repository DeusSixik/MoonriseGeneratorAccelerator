package dev.sixik.density_interpreter.tests;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.jetbrains.annotations.Nullable;

public interface NoiseChunkInterface {


    NoiseChunk.BlockStateFiller bts$getRules();

    void fillNoiseGrid(int startX, int startZ);

    double getFinalDensity(int blockX, int blockY, int blockZ);

    void updateCtxData(int x, int y, int z);
}
