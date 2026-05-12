package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.carver.CanyonScratch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Function;

@Mixin(CanyonWorldCarver.class)
public abstract class MixinCanyonWorldCarver extends WorldCarver<CanyonCarverConfiguration> {

    @Unique
    private volatile ThreadLocal<CanyonScratch> ga$canyonScratch;

    private MixinCanyonWorldCarver(Codec<CanyonCarverConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Reuse width-factor buffers and RNG state instead of allocating a new canyon scratch pipeline per tunnel.
     */
    @Overwrite
    public boolean carve(
            CarvingContext carvingContext,
            CanyonCarverConfiguration canyonCarverConfiguration,
            ChunkAccess chunkAccess,
            Function<BlockPos, Holder<Biome>> function,
            RandomSource randomSource,
            Aquifer aquifer,
            ChunkPos chunkPos,
            CarvingMask carvingMask
    ) {
        int rangeBlocks = (this.getRange() * 2 - 1) * 16;
        double x = chunkPos.getBlockX(randomSource.nextInt(16));
        double y = canyonCarverConfiguration.y.sample(randomSource, carvingContext);
        double z = chunkPos.getBlockZ(randomSource.nextInt(16));
        float yaw = randomSource.nextFloat() * ((float) Math.PI * 2.0F);
        float pitch = canyonCarverConfiguration.verticalRotation.sample(randomSource);
        double yScale = canyonCarverConfiguration.yScale.sample(randomSource);
        float thickness = canyonCarverConfiguration.shape.thickness.sample(randomSource);
        int maxStep = (int) (rangeBlocks * canyonCarverConfiguration.shape.distanceFactor.sample(randomSource));
        this.ga$doCarveDod(
                carvingContext,
                canyonCarverConfiguration,
                chunkAccess,
                function,
                randomSource.nextLong(),
                aquifer,
                x,
                y,
                z,
                thickness,
                yaw,
                pitch,
                maxStep,
                yScale,
                carvingMask
        );
        return true;
    }

    @Unique
    private void ga$doCarveDod(
            CarvingContext carvingContext,
            CanyonCarverConfiguration canyonCarverConfiguration,
            ChunkAccess chunkAccess,
            Function<BlockPos, Holder<Biome>> function,
            long seed,
            Aquifer aquifer,
            double x,
            double y,
            double z,
            float thickness,
            float yaw,
            float pitch,
            int maxStep,
            double yScale,
            CarvingMask carvingMask
    ) {
        CanyonScratch scratch = this.ga$getCanyonScratch();
        LegacyRandomSource random = scratch.random;
        random.setSeed(seed);
        float[] widthFactors = scratch.ensureWidthFactors(carvingContext.getGenDepth());
        this.ga$fillWidthFactors(widthFactors, carvingContext.getGenDepth(), canyonCarverConfiguration, random);
        scratch.skipChecker.setup(carvingContext.getMinGenY(), widthFactors);

        float yawDelta = 0.0F;
        float pitchDelta = 0.0F;

        for (int step = 0; step < maxStep; step++) {
            double horizontalRadius = 1.5D + (double) (Mth.sin((float) step * (float) Math.PI / (float) maxStep) * thickness);
            double verticalRadius = horizontalRadius * yScale;
            horizontalRadius *= canyonCarverConfiguration.shape.horizontalRadiusFactor.sample(random);
            verticalRadius = this.ga$updateVerticalRadius(canyonCarverConfiguration, random, verticalRadius, maxStep, step);

            float cosPitch = Mth.cos(pitch);
            float sinPitch = Mth.sin(pitch);
            x += (double) (Mth.cos(yaw) * cosPitch);
            y += (double) sinPitch;
            z += (double) (Mth.sin(yaw) * cosPitch);

            pitch *= 0.7F;
            pitch += pitchDelta * 0.05F;
            yaw += yawDelta * 0.05F;
            pitchDelta *= 0.8F;
            yawDelta *= 0.5F;
            pitchDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0F;
            yawDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0F;

            if (random.nextInt(4) == 0) {
                continue;
            }

            if (!canReach(chunkAccess.getPos(), x, z, step, maxStep, thickness)) {
                return;
            }

            this.carveEllipsoid(
                    carvingContext,
                    canyonCarverConfiguration,
                    chunkAccess,
                    function,
                    aquifer,
                    x,
                    y,
                    z,
                    horizontalRadius,
                    verticalRadius,
                    carvingMask,
                    scratch.skipChecker
            );
        }
    }

    @Unique
    private void ga$fillWidthFactors(
            float[] widthFactors,
            int genDepth,
            CanyonCarverConfiguration canyonCarverConfiguration,
            LegacyRandomSource random
    ) {
        float width = 1.0F;
        for (int i = 0; i < genDepth; i++) {
            if (i == 0 || random.nextInt(canyonCarverConfiguration.shape.widthSmoothness) == 0) {
                float sampled = random.nextFloat();
                width = 1.0F + sampled * sampled;
            }
            widthFactors[i] = width * width;
        }
    }

    @Unique
    private double ga$updateVerticalRadius(
            CanyonCarverConfiguration canyonCarverConfiguration,
            LegacyRandomSource random,
            double verticalRadius,
            float maxStep,
            float step
    ) {
        float centerWeight = 1.0F - Mth.abs(0.5F - step / maxStep) * 2.0F;
        float scale = canyonCarverConfiguration.shape.verticalRadiusDefaultFactor
                + canyonCarverConfiguration.shape.verticalRadiusCenterFactor * centerWeight;
        return (double) scale * verticalRadius * (double) Mth.randomBetween(random, 0.75F, 1.0F);
    }

    @Unique
    private CanyonScratch ga$getCanyonScratch() {
        ThreadLocal<CanyonScratch> scratch = this.ga$canyonScratch;
        if (scratch == null) {
            synchronized (this) {
                scratch = this.ga$canyonScratch;
                if (scratch == null) {
                    scratch = ThreadLocal.withInitial(CanyonScratch::new);
                    this.ga$canyonScratch = scratch;
                }
            }
        }
        return scratch.get();
    }
}
