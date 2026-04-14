package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.features.ChunkAccess$getOrCreateHeightmapUnsynchronized;
import dev.sixik.generator_accelerator.common.features.FastTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.*;
import java.util.function.Function;

@Mixin(OreFeature.class)
public abstract class MixinOreFeature extends Feature<OreConfiguration> {

    private MixinOreFeature(Codec<OreConfiguration> codec) {
        super(codec);
    }

    @Unique
    private static final ThreadLocal<BitSet> BTS$SHARED_BITSET = ThreadLocal.withInitial(BitSet::new);

    @Unique
    private static final Vec3i[] BTS$DIRECTIONS =
            Arrays.stream(Direction.values()).map(Direction::getNormal).toList().toArray(new Vec3i[0]);

    @Unique
    private static final ThreadLocal<IdentityHashMap<RuleTest, Block[]>> BTS$RULE_CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    @Unique
    private static final Block[] BTS$COMPLEX_RULE_MARKER = new Block[0];

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<OreConfiguration> placeContext) {
        RandomSource randomsource = placeContext.random();
        BlockPos blockpos = placeContext.origin();
        WorldGenLevel worldgenlevel = placeContext.level();
        OreConfiguration oreconfiguration = placeContext.config();
        float f = randomsource.nextFloat() * (float) Math.PI;
        float f1 = oreconfiguration.size / 8.0F;
        int i = Mth.ceil((oreconfiguration.size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double d0 = blockpos.getX() + Math.sin(f) * f1;
        double d1 = blockpos.getX() - Math.sin(f) * f1;
        double d2 = blockpos.getZ() + Math.cos(f) * f1;
        double d3 = blockpos.getZ() - Math.cos(f) * f1;
        int j = 2;
        double d4 = blockpos.getY() + randomsource.nextInt(3) - 2;
        double d5 = blockpos.getY() + randomsource.nextInt(3) - 2;
        int k = blockpos.getX() - Mth.ceil(f1) - i;
        int l = blockpos.getY() - 2 - i;
        int i1 = blockpos.getZ() - Mth.ceil(f1) - i;
        int j1 = 2 * (Mth.ceil(f1) + i);
        int k1 = 2 * (2 + i);

        ChunkAccess cachedChunk = null;
        Heightmap cachedHeightmap = null;
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;

        for (int l1 = k; l1 <= k + j1; l1++) {
            for (int i2 = i1; i2 <= i1 + j1; i2++) {

                int currentChunkX = l1 >> 4;
                int currentChunkZ = i2 >> 4;

                if (currentChunkX != lastChunkX || currentChunkZ != lastChunkZ) {
                    cachedChunk = worldgenlevel.getChunk(currentChunkX, currentChunkZ);
                    cachedHeightmap = ((ChunkAccess$getOrCreateHeightmapUnsynchronized)cachedChunk).bts$getOrCreateHeightmapUnsynchronized(Heightmap.Types.OCEAN_FLOOR_WG);
                    lastChunkX = currentChunkX;
                    lastChunkZ = currentChunkZ;
                }

                int height = cachedHeightmap.getFirstAvailable(l1 & 15, i2 & 15) - 1;

                if (l <= height) {
                    return this.doPlace(worldgenlevel, randomsource, oreconfiguration, d0, d1, d2, d3, d4, d5, k, l, i1, j1, k1);
                }
            }
        }

        return false;
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public boolean doPlace(
            WorldGenLevel pLevel,
            RandomSource pRandom,
            OreConfiguration pConfig,
            double pMinX,
            double pMaxX,
            double pMinZ,
            double pMaxZ,
            double pMinY,
            double pMaxY,
            int pX,
            int pY,
            int pZ,
            int pWidth,
            int pHeight
    ) {
        int placedCount = 0;

        BitSet bitset = BTS$SHARED_BITSET.get();
        if (bitset.size() > 262_144) {
            bitset = new BitSet();
            BTS$SHARED_BITSET.set(bitset);
        } else {
            bitset.clear();
        }

        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
        int j = pConfig.size;
        double[] adouble = new double[j * 4];

        for (int k = 0; k < j; k++) {
            final int mul = k * 4;
            float f = (float)k / j;
            double d0 = Mth.lerp(f, pMinX, pMaxX);
            double d1 = Mth.lerp(f, pMinY, pMaxY);
            double d2 = Mth.lerp(f, pMinZ, pMaxZ);
            double d3 = pRandom.nextDouble() * j / 16.0;
            double d4 = ((Mth.sin((float) Math.PI * f) + 1.0F) * d3 + 1.0) / 2.0;
            adouble[mul] = d0;
            adouble[mul + 1] = d1;
            adouble[mul + 2] = d2;
            adouble[mul + 3] = d4;
        }

        for (int l3 = 0; l3 < j - 1; l3++) {
            final int mul = l3 * 4;
            if (!(adouble[mul + 3] <= 0.0)) {
                for (int i4 = l3 + 1; i4 < j; i4++) {
                    final int mul2 = i4 * 4;
                    if (!(adouble[mul2 + 3] <= 0.0)) {
                        double d8 = adouble[mul] - adouble[mul2];
                        double d10 = adouble[mul + 1] - adouble[mul2 + 1];
                        double d12 = adouble[mul + 2] - adouble[mul2 + 2];
                        double d14 = adouble[mul + 3] - adouble[mul2 + 3];
                        if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12) {
                            if (d14 > 0.0) adouble[mul2 + 3] = -1.0;
                            else adouble[mul + 3] = -1.0;
                        }
                    }
                }
            }
        }

        FastTarget[] fastTargets = new FastTarget[pConfig.targetStates.size()];
        for (int i = 0; i < pConfig.targetStates.size(); i++) {
            OreConfiguration.TargetBlockState target = pConfig.targetStates.get(i);
            RuleTest rule = target.target;

            Block[] extractedBlocks = null;

            if (rule instanceof BlockMatchTest bmt) {
                extractedBlocks = new Block[] { bmt.block };
            } else if (rule instanceof TagMatchTest tmt) {
                Iterable<Holder<Block>> tagElements = BuiltInRegistries.BLOCK.getTagOrEmpty(tmt.tag);

                List<Block> tempBlocks = new ArrayList<>();
                for (Holder<Block> holder : tagElements) {
                    tempBlocks.add(holder.value());
                }

                extractedBlocks = tempBlocks.toArray(new Block[0]);
            }

            fastTargets[i] = new FastTarget(extractedBlocks, extractedBlocks == null ? rule : null, target.state);
        }


        int levelMinY = pLevel.getMinBuildHeight();
        int levelMaxY = pLevel.getMaxBuildHeight();

        float airChance = pConfig.discardChanceOnAirExposure;
        try (BulkSectionAccess bulksectionaccess = new BulkSectionAccess(pLevel)) {
            for (int j4 = 0; j4 < j; j4++) {
                final int mul = j4 * 4;
                double radius = adouble[mul + 3];
                if (radius < 0.0) continue;

                double centerX = adouble[mul];
                double centerY = adouble[mul + 1];
                double centerZ = adouble[mul + 2];

                int minX = Math.max(Mth.floor(centerX - radius), pX);
                int minY = Math.max(Mth.floor(centerY - radius), pY);
                int minZ = Math.max(Mth.floor(centerZ - radius), pZ);
                int maxX = Math.max(Mth.floor(centerX + radius), minX);
                int maxY = Math.max(Mth.floor(centerY + radius), minY);
                int maxZ = Math.max(Mth.floor(centerZ + radius), minZ);

                minY = Math.max(minY, levelMinY);
                maxY = Math.min(maxY, levelMaxY);

                double invRadius = 1.0 / radius;
                double offsetX = 0.5 - centerX;
                double offsetY = 0.5 - centerY;
                double offsetZ = 0.5 - centerZ;

                LevelChunkSection cachedSection = null;
                int lastSecX = Integer.MIN_VALUE;
                int lastSecY = Integer.MIN_VALUE;
                int lastSecZ = Integer.MIN_VALUE;

                for (int currY = minY; currY <= maxY; currY++) {
                    double dy = (currY + offsetY) * invRadius;
                    double dySq = dy * dy;
                    if (dySq >= 1.0) continue;

                    int bitIndexY = (currY - pY) * pWidth;
                    int secY = currY >> 4;

                    for (int currZ = minZ; currZ <= maxZ; currZ++) {
                        double dz = (currZ + offsetZ) * invRadius;
                        double dyzSq = dySq + dz * dz;
                        if (dyzSq >= 1.0) continue;

                        int bitIndexYZ = bitIndexY + (currZ - pZ) * pWidth * pHeight;
                        int secZ = currZ >> 4;

                        for (int currX = minX; currX <= maxX; currX++) {
                            double dx = (currX + offsetX) * invRadius;

                            if (dx * dx + dyzSq < 1.0) {
                                int bitIndex = (currX - pX) + bitIndexYZ;

                                if (!bitset.get(bitIndex)) {
                                    bitset.set(bitIndex);

                                    int secX = currX >> 4;
                                    if (secX != lastSecX || secY != lastSecY || secZ != lastSecZ) {
                                        blockpos$mutableblockpos.set(currX, currY, currZ);
                                        cachedSection = bulksectionaccess.getSection(blockpos$mutableblockpos);
                                        lastSecX = secX;
                                        lastSecY = secY;
                                        lastSecZ = secZ;
                                    }

                                    if (cachedSection != null) {
                                        int i3 = currX & 15;
                                        int j3 = currY & 15;
                                        int k3 = currZ & 15;
                                        BlockState blockstate = cachedSection.getBlockState(i3, j3, k3);
                                        Block currentBlock = blockstate.getBlock();

                                        for (int t = 0; t < fastTargets.length; t++) {
                                            FastTarget target = fastTargets[t];
                                            boolean matched = false;

                                            if (target.validBlocks() != null) {
                                                for (int b = 0; b < target.validBlocks().length; b++) {
                                                    if (currentBlock == target.validBlocks()[b]) { // Сравнение указателей!
                                                        matched = true;
                                                        break;
                                                    }
                                                }
                                            } else {
                                                matched = target.fallbackRule().test(blockstate, pRandom);
                                            }

                                            if (matched) {
                                                boolean skipAirCheck = (airChance <= 0.0F) || (airChance < 1.0F && pRandom.nextFloat() < airChance);

                                                blockpos$mutableblockpos.set(currX, currY, currZ);
                                                if (skipAirCheck || !isAdjacentToAir(bulksectionaccess::getBlockState, blockpos$mutableblockpos)) {
                                                    cachedSection.setBlockState(i3, j3, k3, target.placementState(), false);
                                                    placedCount++;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return placedCount > 0;
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public static boolean canPlaceOre(
            BlockState pState,
            Function<BlockPos, BlockState> pAdjacentStateAccessor,
            RandomSource pRandom,
            OreConfiguration pConfig,
            OreConfiguration.TargetBlockState pTargetState,
            BlockPos.MutableBlockPos pMutablePos
    ) {
        RuleTest rule = pTargetState.target;
        IdentityHashMap<RuleTest, Block[]> cache = BTS$RULE_CACHE.get();

        Block[] validBlocks = cache.computeIfAbsent(rule, MixinOreFeature::bts$unwrapRule);

        boolean matched = false;

        if (validBlocks != BTS$COMPLEX_RULE_MARKER) {
            Block currentBlock = pState.getBlock();
            for (int i = 0; i < validBlocks.length; i++) {
                if (currentBlock == validBlocks[i]) {
                    matched = true;
                    break;
                }
            }
        } else {
            matched = rule.test(pState, pRandom);
        }

        if (!matched) {
            return false;
        } else {
            float chance = pConfig.discardChanceOnAirExposure;
            boolean skipAir = (chance <= 0.0F) || (chance < 1.0F && pRandom.nextFloat() < chance);

            return skipAir || !fastIsAdjacentToAir(pAdjacentStateAccessor, pMutablePos);
        }
    }

    @Unique
    private static Block[] bts$unwrapRule(RuleTest rule) {
        if (rule instanceof BlockMatchTest bmt) {
            return new Block[] { bmt.block };
        } else if (rule instanceof TagMatchTest tmt) {
            Iterable<Holder<Block>> tags = BuiltInRegistries.BLOCK.getTagOrEmpty(tmt.tag);
            List<Block> list = new ArrayList<>();
            for (Holder<Block> holder : tags) {
                list.add(holder.value());
            }
            return list.toArray(new Block[0]);
        }
        return BTS$COMPLEX_RULE_MARKER;
    }

    @Unique
    private static boolean fastIsAdjacentToAir(Function<BlockPos, BlockState> pAdjacentStateAccessor, BlockPos.MutableBlockPos pPos) {
        int x = pPos.getX();
        int y = pPos.getY();
        int z = pPos.getZ();

        final Vec3i[] dir = BTS$DIRECTIONS;
        for (int i = 0; i < dir.length; i++) {
            pPos.setWithOffset(dir[i], x, y, z);
            if (pAdjacentStateAccessor.apply(pPos).isAir()) {
                pPos.set(x, y, z);
                return true;
            }
        }

        pPos.set(x, y, z);
        return false;
    }

}
