package dev.sixik.generator_accelerator.common.surface;

import dev.sixik.generator_accelerator.common.surface.compiler.SurfaceScratch;
import dev.sixik.generator_accelerator.common.surface.vector.VectorBlockColumn;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public final class GASurfaceThreadState {
    public final SurfaceScratch surfaceScratch = new SurfaceScratch();
    public final Holder<Biome>[] surfaceBiomes = new Holder[256];
    public final GASurfaceChunkBiomeLookup chunkBiomeLookup = new GASurfaceChunkBiomeLookup();
    public final BlockPos.MutableBlockPos columnPos = new BlockPos.MutableBlockPos();
    public final BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();
    public VectorChunkContext vectorContext;
    public VectorBlockColumn vectorColumn;
}
