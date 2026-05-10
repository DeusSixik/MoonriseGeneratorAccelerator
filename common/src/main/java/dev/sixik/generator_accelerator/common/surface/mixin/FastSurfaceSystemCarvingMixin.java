package dev.sixik.generator_accelerator.common.surface.mixin;

import dev.sixik.generator_accelerator.common.surface.FastSurfaceSystemCarvingCacheEntry;
import dev.sixik.generator_accelerator.common.utils.FastPositionalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.function.Function;

@Mixin(SurfaceSystem.class)
public class FastSurfaceSystemCarvingMixin {

    @Shadow
    @Final
    public PositionalRandomFactory noiseRandom;

    @Shadow
    @Final
    private NormalNoise surfaceNoise;

    @Unique
    private final ThreadLocal<FastSurfaceSystemCarvingCacheEntry> bts$cache =
            ThreadLocal.withInitial(FastSurfaceSystemCarvingCacheEntry::new);

    /**
     * @author Sixik
     * @reason Caching the AST tree and replacing 3D climate calculations with reading from the chunk palette (O(1))
     */
    @Deprecated
    @Overwrite
    public Optional<BlockState> topMaterial(SurfaceRules.RuleSource ruleSource, CarvingContext carvingContext, Function<BlockPos, Holder<Biome>> function, ChunkAccess chunkAccess, NoiseChunk noiseChunk, BlockPos blockPos, boolean bl) {
        FastSurfaceSystemCarvingCacheEntry cache = this.bts$cache.get();
        SurfaceRules.Context context = cache.context.get();
        SurfaceRules.SurfaceRule surfaceRule = cache.rule.get();

        if (context == null || surfaceRule == null || cache.chunk.get() != chunkAccess) {
            Function<BlockPos, Holder<Biome>> fastBiomeGetter = pos ->
                    chunkAccess.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);

            context = new SurfaceRules.Context((SurfaceSystem) (Object) this, carvingContext.randomState(), chunkAccess, noiseChunk, fastBiomeGetter, carvingContext.registryAccess().registryOrThrow(Registries.BIOME), carvingContext);
            surfaceRule = ruleSource.apply(context);
            cache.chunk = new WeakReference<>(chunkAccess);
            cache.context = new WeakReference<>(context);
            cache.rule = new WeakReference<>(surfaceRule);
        }

        int i = blockPos.getX();
        int j = blockPos.getY();
        int k = blockPos.getZ();

        context.updateXZ(i, k);
        context.updateY(1, 1, bl ? j + 1 : Integer.MIN_VALUE, i, j, k);

        BlockState blockState = surfaceRule.tryApply(i, j, k);
        return Optional.ofNullable(blockState);
    }

    /**
     * @author Sixik
     * @reason Avoid allocating a RandomSource for every surface-depth column sample.
     */
    @Overwrite
    public int getSurfaceDepth(int i, int j) {
        double d = this.surfaceNoise.getValue(i, 0.0, j);
        return (int) (d * 2.75 + 3.0 + FastPositionalRandom.nextDoubleAt(this.noiseRandom, i, 0, j) * 0.25);
    }
}
