package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.carver.CaveSkipChecker;
import dev.sixik.generator_accelerator.common.carver.CaveTunnelBatch;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinCaveCarverConfigurationAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Function;

@Mixin(CaveWorldCarver.class)
public abstract class MixinCaveWorldCarver extends WorldCarver<CaveCarverConfiguration> {

    @Unique
    private static final float GA$HALF_PI = (float) (Math.PI * 0.5D);

    @Unique
    private static final int GA$FAST_TUNNEL_STRIDE = Math.max(1, Integer.getInteger("ga.carver.fastTunnelStride", 2));

    @Unique
    private static final double GA$FAST_TUNNEL_MIN_RADIUS = Double.parseDouble(System.getProperty("ga.carver.fastTunnelMinRadius", "1.85"));

    @Unique
    private ThreadLocal<CaveTunnelBatch> ga$caveTunnelBatch;

    @Unique
    private ThreadLocal<CaveSkipChecker> ga$caveSkipChecker;

    private MixinCaveWorldCarver(Codec<CaveCarverConfiguration> codec) {
        super(codec);
    }

    @Shadow
    protected abstract int getCaveBound();

    @Shadow
    protected abstract float getThickness(RandomSource randomSource);

    @Shadow
    protected abstract double getYScale();

    /**
     * @author Sixik
     * @reason Replace lambda-heavy recursive tunnel carving with a primitive stack and reusable skip checker.
     */
    @Overwrite
    public boolean carve(
            CarvingContext carvingContext,
            CaveCarverConfiguration caveCarverConfiguration,
            ChunkAccess chunkAccess,
            Function<BlockPos, Holder<Biome>> function,
            RandomSource randomSource,
            Aquifer aquifer,
            ChunkPos chunkPos,
            CarvingMask carvingMask
    ) {
        int rangeBlocks = SectionPos.sectionToBlockCoord(this.getRange() * 2 - 1);
        int caveCount = randomSource.nextInt(randomSource.nextInt(randomSource.nextInt(this.getCaveBound()) + 1) + 1);
        CaveSkipChecker skipChecker = this.ga$getCaveSkipChecker();

        for (int caveIndex = 0; caveIndex < caveCount; caveIndex++) {
            double x = chunkPos.getBlockX(randomSource.nextInt(16));
            double y = caveCarverConfiguration.y.sample(randomSource, carvingContext);
            double z = chunkPos.getBlockZ(randomSource.nextInt(16));
            double horizontalRadiusMultiplier = caveCarverConfiguration.horizontalRadiusMultiplier.sample(randomSource);
            double verticalRadiusMultiplier = caveCarverConfiguration.verticalRadiusMultiplier.sample(randomSource);
            skipChecker.setup(((MixinCaveCarverConfigurationAccessor) caveCarverConfiguration).ga$getFloorLevel().sample(randomSource));

            int tunnelCount = 1;
            if (randomSource.nextInt(4) == 0) {
                double roomYScale = caveCarverConfiguration.yScale.sample(randomSource);
                float roomSize = 1.0F + randomSource.nextFloat() * 6.0F;
                this.createRoom(
                        carvingContext,
                        caveCarverConfiguration,
                        chunkAccess,
                        function,
                        aquifer,
                        x,
                        y,
                        z,
                        roomSize,
                        roomYScale,
                        carvingMask,
                        skipChecker
                );
                tunnelCount += randomSource.nextInt(4);
            }

            for (int tunnelIndex = 0; tunnelIndex < tunnelCount; tunnelIndex++) {
                float yaw = randomSource.nextFloat() * ((float) Math.PI * 2.0F);
                float pitch = (randomSource.nextFloat() - 0.5F) * 0.25F;
                float thickness = this.getThickness(randomSource);
                int maxStep = rangeBlocks - randomSource.nextInt(rangeBlocks / 4);
                this.createTunnel(
                        carvingContext,
                        caveCarverConfiguration,
                        chunkAccess,
                        function,
                        randomSource.nextLong(),
                        aquifer,
                        x,
                        y,
                        z,
                        horizontalRadiusMultiplier,
                        verticalRadiusMultiplier,
                        thickness,
                        yaw,
                        pitch,
                        0,
                        maxStep,
                        this.getYScale(),
                        carvingMask,
                        skipChecker
                );
            }
        }

        return true;
    }

    /**
     * @author Sixik
     * @reason Keep room carving on the same reusable checker path and avoid redundant trig work.
     */
    @Overwrite
    protected void createRoom(
            CarvingContext carvingContext,
            CaveCarverConfiguration caveCarverConfiguration,
            ChunkAccess chunkAccess,
            Function<BlockPos, Holder<Biome>> function,
            Aquifer aquifer,
            double x,
            double y,
            double z,
            float size,
            double yScale,
            CarvingMask carvingMask,
            WorldCarver.CarveSkipChecker carveSkipChecker
    ) {
        double horizontalRadius = 1.5D + (double) size;
        this.carveEllipsoid(
                carvingContext,
                caveCarverConfiguration,
                chunkAccess,
                function,
                aquifer,
                x + 1.0D,
                y,
                z,
                horizontalRadius,
                horizontalRadius * yScale,
                carvingMask,
                carveSkipChecker
        );
    }

    /**
     * @author Sixik
     * @reason Replace recursive branching with a DOD tunnel stack backed by primitive arrays.
     */
    @Overwrite
    protected void createTunnel(
            CarvingContext carvingContext,
            CaveCarverConfiguration caveCarverConfiguration,
            ChunkAccess chunkAccess,
            Function<BlockPos, Holder<Biome>> function,
            long seed,
            Aquifer aquifer,
            double x,
            double y,
            double z,
            double horizontalRadiusMultiplier,
            double verticalRadiusMultiplier,
            float thickness,
            float yaw,
            float pitch,
            int minStep,
            int maxStep,
            double yScale,
            CarvingMask carvingMask,
            WorldCarver.CarveSkipChecker carveSkipChecker
    ) {
        CaveTunnelBatch batch = this.ga$getCaveTunnelBatch();
        batch.clear();
        batch.push(
                seed,
                x,
                y,
                z,
                horizontalRadiusMultiplier,
                verticalRadiusMultiplier,
                thickness,
                yaw,
                pitch,
                minStep,
                maxStep,
                yScale
        );

        while (!batch.isEmpty()) {
            int index = batch.pop();
            LegacyRandomSource random = batch.random(index);
            random.setSeed(batch.seeds[index]);

            double tunnelX = batch.xs[index];
            double tunnelY = batch.ys[index];
            double tunnelZ = batch.zs[index];
            double tunnelHorizontalRadiusMultiplier = batch.horizontalRadiusMultipliers[index];
            double tunnelVerticalRadiusMultiplier = batch.verticalRadiusMultipliers[index];
            float tunnelThickness = batch.thicknesses[index];
            float tunnelYaw = batch.yaws[index];
            float tunnelPitch = batch.pitches[index];
            int startStep = batch.startSteps[index];
            int endStep = batch.endSteps[index];
            double tunnelYScale = batch.yScales[index];

            int splitStep = random.nextInt(endStep / 2) + endStep / 4;
            boolean slowPitchDamping = random.nextInt(6) == 0;
            float yawDelta = 0.0F;
            float pitchDelta = 0.0F;
            int tunnelStride = GA$FAST_TUNNEL_STRIDE;

            for (int step = startStep; step < endStep; step++) {
                double horizontalRadius = 1.5D + (double) (Mth.sin((float) Math.PI * (float) step / (float) endStep) * tunnelThickness);
                double verticalRadius = horizontalRadius * tunnelYScale;
                float cosPitch = Mth.cos(tunnelPitch);
                tunnelX += (double) (Mth.cos(tunnelYaw) * cosPitch);
                tunnelY += (double) Mth.sin(tunnelPitch);
                tunnelZ += (double) (Mth.sin(tunnelYaw) * cosPitch);

                tunnelPitch *= slowPitchDamping ? 0.92F : 0.7F;
                tunnelPitch += pitchDelta * 0.1F;
                tunnelYaw += yawDelta * 0.1F;
                pitchDelta *= 0.9F;
                yawDelta *= 0.75F;
                pitchDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0F;
                yawDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0F;

                if (step == splitStep && tunnelThickness > 1.0F) {
                    float branchPitch = tunnelPitch / 3.0F;
                    int branchStartStep = step;
                    long leftSeed = random.nextLong();
                    float leftThickness = random.nextFloat() * 0.5F + 0.5F;
                    long rightSeed = random.nextLong();
                    float rightThickness = random.nextFloat() * 0.5F + 0.5F;
                    batch.push(
                            rightSeed,
                            tunnelX,
                            tunnelY,
                            tunnelZ,
                            tunnelHorizontalRadiusMultiplier,
                            tunnelVerticalRadiusMultiplier,
                            rightThickness,
                            tunnelYaw + GA$HALF_PI,
                            branchPitch,
                            branchStartStep,
                            endStep,
                            1.0D
                    );
                    batch.push(
                            leftSeed,
                            tunnelX,
                            tunnelY,
                            tunnelZ,
                            tunnelHorizontalRadiusMultiplier,
                            tunnelVerticalRadiusMultiplier,
                            leftThickness,
                            tunnelYaw - GA$HALF_PI,
                            branchPitch,
                            branchStartStep,
                            endStep,
                            1.0D
                    );
                    break;
                }

                if (random.nextInt(4) == 0) {
                    continue;
                }

                if (!canReach(chunkAccess.getPos(), tunnelX, tunnelZ, step, endStep, tunnelThickness)) {
                    break;
                }

                double effectiveHorizontalRadius = horizontalRadius * tunnelHorizontalRadiusMultiplier;
                boolean shouldCarveStep = tunnelStride <= 1
                        || step == startStep
                        || step + 1 >= endStep
                        || step == splitStep
                        || effectiveHorizontalRadius < GA$FAST_TUNNEL_MIN_RADIUS
                        || (step - startStep) % tunnelStride == 0;
                if (!shouldCarveStep) {
                    continue;
                }

                this.carveEllipsoid(
                        carvingContext,
                        caveCarverConfiguration,
                        chunkAccess,
                        function,
                        aquifer,
                        tunnelX,
                        tunnelY,
                        tunnelZ,
                        effectiveHorizontalRadius,
                        verticalRadius * tunnelVerticalRadiusMultiplier,
                        carvingMask,
                        carveSkipChecker
                );
            }
        }
    }

    @Unique
    private CaveTunnelBatch ga$getCaveTunnelBatch() {
        ThreadLocal<CaveTunnelBatch> batch = this.ga$caveTunnelBatch;
        if (batch == null) {
            batch = ThreadLocal.withInitial(CaveTunnelBatch::new);
            this.ga$caveTunnelBatch = batch;
        }
        return batch.get();
    }

    @Unique
    private CaveSkipChecker ga$getCaveSkipChecker() {
        ThreadLocal<CaveSkipChecker> checker = this.ga$caveSkipChecker;
        if (checker == null) {
            checker = ThreadLocal.withInitial(CaveSkipChecker::new);
            this.ga$caveSkipChecker = checker;
        }
        return checker.get();
    }
}
