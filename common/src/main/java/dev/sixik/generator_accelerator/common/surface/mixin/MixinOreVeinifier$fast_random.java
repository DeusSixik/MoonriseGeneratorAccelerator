package dev.sixik.generator_accelerator.common.surface.mixin;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OreVeinifier.class)
public abstract class MixinOreVeinifier$fast_random {
    @Unique
    private static final float GA$FLOAT_UNIT = 5.9604645E-8f;
    @Unique
    private static final long GA$LEGACY_MASK = 0xFFFFFFFFFFFFL;
    @Unique
    private static final long GA$LEGACY_MULTIPLIER = 25214903917L;
    @Unique
    private static final long GA$LEGACY_INCREMENT = 11L;
    @Unique
    private static final long GA$XOROSHIRO_GOLDEN_RATIO_64 = -7046029254386353131L;
    @Unique
    private static final long GA$XOROSHIRO_SILVER_RATIO_64 = 7640891576956012809L;
    @Unique
    private static final BlockState GA$COPPER_ORE = Blocks.COPPER_ORE.defaultBlockState();
    @Unique
    private static final BlockState GA$RAW_COPPER_BLOCK = Blocks.RAW_COPPER_BLOCK.defaultBlockState();
    @Unique
    private static final BlockState GA$GRANITE = Blocks.GRANITE.defaultBlockState();
    @Unique
    private static final BlockState GA$DEEPSLATE_IRON_ORE = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
    @Unique
    private static final BlockState GA$RAW_IRON_BLOCK = Blocks.RAW_IRON_BLOCK.defaultBlockState();
    @Unique
    private static final BlockState GA$TUFF = Blocks.TUFF.defaultBlockState();

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void ga$createFastOreVeinifier(
            DensityFunction veininess,
            DensityFunction veinA,
            DensityFunction veinB,
            PositionalRandomFactory randomFactory,
            CallbackInfoReturnable<NoiseChunk.BlockStateFiller> cir
    ) {
        cir.setReturnValue(context -> ga$calculate(veininess, veinA, veinB, randomFactory, context));
    }

    @Unique
    private static BlockState ga$calculate(
            DensityFunction veininess,
            DensityFunction veinA,
            DensityFunction veinB,
            PositionalRandomFactory randomFactory,
            DensityFunction.FunctionContext context
    ) {
        int y = context.blockY();
        if (y < -60 || y > 50 || (y > -8 && y < 0)) {
            return null;
        }

        double vein = veininess.compute(context);
        boolean copper = vein > 0.0;
        double absoluteVein = Math.abs(vein);
        int maxY = copper ? 50 : -8;
        int minY = copper ? 0 : -60;
        int distanceToTop = maxY - y;
        int distanceToBottom = y - minY;
        if (distanceToBottom < 0 || distanceToTop < 0) {
            return null;
        }

        double edgeRoundoff = Mth.clampedMap(
                (double) Math.min(distanceToTop, distanceToBottom),
                0.0,
                20.0,
                -0.2,
                0.0
        );
        if (absoluteVein + edgeRoundoff < 0.4000000059604645D) {
            return null;
        }

        int x = context.blockX();
        int z = context.blockZ();
        if (randomFactory instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xoroshiro) {
            return ga$calculateXoroshiro(copper, absoluteVein, veinA, veinB, xoroshiro, context, x, y, z);
        }
        if (randomFactory instanceof LegacyRandomSource.LegacyPositionalRandomFactory legacy) {
            return ga$calculateLegacy(copper, absoluteVein, veinA, veinB, legacy, context, x, y, z);
        }
        return ga$calculateFallback(copper, absoluteVein, veinA, veinB, randomFactory.at(x, y, z), context);
    }

    @Unique
    private static BlockState ga$calculateXoroshiro(
            boolean copper,
            double absoluteVein,
            DensityFunction veinA,
            DensityFunction veinB,
            XoroshiroRandomSource.XoroshiroPositionalRandomFactory randomFactory,
            DensityFunction.FunctionContext context,
            int x,
            int y,
            int z
    ) {
        long seedLo = Mth.getSeed(x, y, z) ^ randomFactory.seedLo;
        long seedHi = randomFactory.seedHi;
        if ((seedLo | seedHi) == 0L) {
            seedLo = GA$XOROSHIRO_GOLDEN_RATIO_64;
            seedHi = GA$XOROSHIRO_SILVER_RATIO_64;
        }

        long lo = seedLo;
        long hi = seedHi;
        long next = Long.rotateLeft(lo + hi, 17) + lo;
        hi ^= lo;
        lo = Long.rotateLeft(lo, 49) ^ hi ^ hi << 21;
        hi = Long.rotateLeft(hi, 28);
        if ((float) (next >>> 40) * GA$FLOAT_UNIT > 0.7F) {
            return null;
        }

        if (veinA.compute(context) >= 0.0) {
            return null;
        }

        double richnessThreshold = Mth.clampedMap(
                absoluteVein,
                0.4000000059604645D,
                0.6000000238418579D,
                0.10000000149011612D,
                0.30000001192092896D
        );
        next = Long.rotateLeft(lo + hi, 17) + lo;
        hi ^= lo;
        lo = Long.rotateLeft(lo, 49) ^ hi ^ hi << 21;
        hi = Long.rotateLeft(hi, 28);
        if ((double) ((float) (next >>> 40) * GA$FLOAT_UNIT) < richnessThreshold
                && veinB.compute(context) > -0.30000001192092896D) {
            next = Long.rotateLeft(lo + hi, 17) + lo;
            float rawRoll = (float) (next >>> 40) * GA$FLOAT_UNIT;
            return rawRoll < 0.02F ? ga$rawOre(copper) : ga$ore(copper);
        }
        return ga$filler(copper);
    }

    @Unique
    private static BlockState ga$calculateLegacy(
            boolean copper,
            double absoluteVein,
            DensityFunction veinA,
            DensityFunction veinB,
            LegacyRandomSource.LegacyPositionalRandomFactory randomFactory,
            DensityFunction.FunctionContext context,
            int x,
            int y,
            int z
    ) {
        long state = ga$legacyInitialState(Mth.getSeed(x, y, z) ^ randomFactory.seed);
        state = ga$legacyNextState(state);
        if ((float) (state >>> 24) * GA$FLOAT_UNIT > 0.7F) {
            return null;
        }

        if (veinA.compute(context) >= 0.0) {
            return null;
        }

        double richnessThreshold = Mth.clampedMap(
                absoluteVein,
                0.4000000059604645D,
                0.6000000238418579D,
                0.10000000149011612D,
                0.30000001192092896D
        );
        state = ga$legacyNextState(state);
        if ((double) ((float) (state >>> 24) * GA$FLOAT_UNIT) < richnessThreshold
                && veinB.compute(context) > -0.30000001192092896D) {
            state = ga$legacyNextState(state);
            float rawRoll = (float) (state >>> 24) * GA$FLOAT_UNIT;
            return rawRoll < 0.02F ? ga$rawOre(copper) : ga$ore(copper);
        }
        return ga$filler(copper);
    }

    @Unique
    private static BlockState ga$calculateFallback(
            boolean copper,
            double absoluteVein,
            DensityFunction veinA,
            DensityFunction veinB,
            RandomSource random,
            DensityFunction.FunctionContext context
    ) {
        if (random.nextFloat() > 0.7F) {
            return null;
        }
        if (veinA.compute(context) >= 0.0) {
            return null;
        }
        double richnessThreshold = Mth.clampedMap(
                absoluteVein,
                0.4000000059604645D,
                0.6000000238418579D,
                0.10000000149011612D,
                0.30000001192092896D
        );
        if ((double) random.nextFloat() < richnessThreshold
                && veinB.compute(context) > -0.30000001192092896D) {
            return random.nextFloat() < 0.02F ? ga$rawOre(copper) : ga$ore(copper);
        }
        return ga$filler(copper);
    }

    @Unique
    private static long ga$legacyInitialState(long seed) {
        return (seed ^ 0x5DEECE66DL) & GA$LEGACY_MASK;
    }

    @Unique
    private static long ga$legacyNextState(long state) {
        return (state * GA$LEGACY_MULTIPLIER + GA$LEGACY_INCREMENT) & GA$LEGACY_MASK;
    }

    @Unique
    private static BlockState ga$ore(boolean copper) {
        return copper ? GA$COPPER_ORE : GA$DEEPSLATE_IRON_ORE;
    }

    @Unique
    private static BlockState ga$rawOre(boolean copper) {
        return copper ? GA$RAW_COPPER_BLOCK : GA$RAW_IRON_BLOCK;
    }

    @Unique
    private static BlockState ga$filler(boolean copper) {
        return copper ? GA$GRANITE : GA$TUFF;
    }
}
