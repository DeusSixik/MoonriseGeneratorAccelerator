package dev.sixik.generator_accelerator.common.surface.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;
import java.util.function.Function;

@Mixin(SurfaceSystem.class)
public class FastSurfaceSystemCarvingMixin {

    @Unique
    private final ThreadLocal<ChunkAccess> bts$cachedChunk = new ThreadLocal<>();

    @Unique
    private final ThreadLocal<SurfaceRules.Context> bts$cachedContext = new ThreadLocal<>();

    @Unique
    private final ThreadLocal<SurfaceRules.SurfaceRule> bts$cachedRule = new ThreadLocal<>();

    /**
     * @author Sixik
     * @reason Caching the AST tree and replacing 3D climate calculations with reading from the chunk palette (O(1))
     */
    @Deprecated
    @Overwrite
    public Optional<BlockState> topMaterial(SurfaceRules.RuleSource ruleSource, CarvingContext carvingContext, Function<BlockPos, Holder<Biome>> function, ChunkAccess chunkAccess, NoiseChunk noiseChunk, BlockPos blockPos, boolean bl) {

        SurfaceRules.Context context = this.bts$cachedContext.get();
        SurfaceRules.SurfaceRule surfaceRule = this.bts$cachedRule.get();

        if (context == null || surfaceRule == null || this.bts$cachedChunk.get() != chunkAccess) {
            Function<BlockPos, Holder<Biome>> fastBiomeGetter = pos ->
                    chunkAccess.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);

            context = new SurfaceRules.Context((SurfaceSystem) (Object) this, carvingContext.randomState(), chunkAccess, noiseChunk, fastBiomeGetter, carvingContext.registryAccess().registryOrThrow(Registries.BIOME), carvingContext);
            surfaceRule = ruleSource.apply(context);

            this.bts$cachedChunk.set(chunkAccess);
            this.bts$cachedContext.set(context);
            this.bts$cachedRule.set(surfaceRule);
        }

        int i = blockPos.getX();
        int j = blockPos.getY();
        int k = blockPos.getZ();

        context.updateXZ(i, k);
        context.updateY(1, 1, bl ? j + 1 : Integer.MIN_VALUE, i, j, k);

        BlockState blockState = surfaceRule.tryApply(i, j, k);
        return Optional.ofNullable(blockState);
    }
}
