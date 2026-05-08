package dev.sixik.generator_accelerator.common.surface;

import dev.sixik.generator_accelerator.common.surface.vector.VectorBlockColumn;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public class SurfaceGenerationState {
    public VectorChunkContext ctx;
    public Holder<Biome>[] surfaceBiomes;
    public VectorBlockColumn fastColumn;
    public BlockPos.MutableBlockPos columnPos;
    public boolean hasFrozenOcean;
}
