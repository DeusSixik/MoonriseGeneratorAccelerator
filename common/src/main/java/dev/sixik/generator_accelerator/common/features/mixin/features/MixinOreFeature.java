package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.features.ChunkAccess$getOrCreateHeightmapUnsynchronized;
import dev.sixik.generator_accelerator.common.features.FastTarget;
import dev.sixik.generator_accelerator.common.features.cache.SharedWeakCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Mixin(value = OreFeature.class, priority = 999)
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
    private static final SharedWeakCache<RuleTest, Block[]> BTS$RULE_CACHE = new SharedWeakCache<>();

    @Unique
    private static final SharedWeakCache<OreConfiguration, CompiledTargets> BTS$TARGET_CACHE = new SharedWeakCache<>();

    @Unique
    private static final SharedWeakCache<Block, StateSet> BTS$BLOCK_STATE_SET_CACHE = new SharedWeakCache<>();

    @Unique
    private static final SharedWeakCache<TagKey<Block>, StateSet> BTS$TAG_STATE_SET_CACHE = new SharedWeakCache<>();

    @Unique
    private static final StateSet BTS$EMPTY_STATE_SET = new StateSet(-1, new int[0], null);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<double[]> BTS$VEIN_DATA =
            ThreadLocal.withInitial(() -> new double[64 * 4]);
    @Unique
    private static final int BTS$MAX_RETAINED_VEIN_DATA_VALUES = 16_384;

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
                    cachedHeightmap =
                            ((ChunkAccess$getOrCreateHeightmapUnsynchronized) cachedChunk)
                                    .bts$getOrCreateHeightmapUnsynchronized(Heightmap.Types.OCEAN_FLOOR_WG);
                    lastChunkX = currentChunkX;
                    lastChunkZ = currentChunkZ;
                }
                boolean aboveFloor;
                if (cachedHeightmap != null) {
                    aboveFloor = l <= (cachedHeightmap.getFirstAvailable(l1 & 15, i2 & 15) - 1);
                } else {
                    aboveFloor = l <= worldgenlevel.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, l1, i2);
                }
                if (aboveFloor) {
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

        BlockPos.MutableBlockPos blockpos$mutableblockpos = BTS$MUTABLE_POS.get();
        int j = pConfig.size;
        double[] adouble = BTS$VEIN_DATA.get();
        if (adouble.length < j * 4) {
            adouble = new double[j * 4];
            BTS$VEIN_DATA.set(adouble);
        }

        for (int k = 0; k < j; k++) {
            int mul = k * 4;
            float f = (float) k / j;
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
            int mul = l3 * 4;
            if (!(adouble[mul + 3] <= 0.0)) {
                for (int i4 = l3 + 1; i4 < j; i4++) {
                    int mul2 = i4 * 4;
                    if (!(adouble[mul2 + 3] <= 0.0)) {
                        double d8 = adouble[mul] - adouble[mul2];
                        double d10 = adouble[mul + 1] - adouble[mul2 + 1];
                        double d12 = adouble[mul + 2] - adouble[mul2 + 2];
                        double d14 = adouble[mul + 3] - adouble[mul2 + 3];
                        if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12) {
                            if (d14 > 0.0) {
                                adouble[mul2 + 3] = -1.0;
                            } else {
                                adouble[mul + 3] = -1.0;
                            }
                        }
                    }
                }
            }
        }

        CompiledTargets compiledTargets = BTS$TARGET_CACHE.getOrCompute(pConfig, MixinOreFeature::bts$compileTargets);
        FastTarget[] fastTargets = compiledTargets.targets();
        boolean hasFallbackTargets = compiledTargets.hasFallbackTargets();
        boolean placementMayBeAir = compiledTargets.placementMayBeAir();
        BlockState[] states = FastBlockStateCache.STATES;
        if (states == null) {
            FastBlockStateCache.init(dev.sixik.generator_accelerator.GeneratorAccelerator.platform);
            states = FastBlockStateCache.STATES;
        }
        boolean[] airStates = FastBlockStateCache.AIR_STATES;

        int levelMinY = pLevel.getMinBuildHeight();
        int levelMaxY = pLevel.getMaxBuildHeight() - 1;
        int planeStride = pWidth * pHeight;
        float airChance = pConfig.discardChanceOnAirExposure;

        try (BulkSectionAccess bulksectionaccess = new BulkSectionAccess(pLevel)) {
            for (int j4 = 0; j4 < j; j4++) {
                int mul = j4 * 4;
                double radius = adouble[mul + 3];
                if (radius < 0.0) {
                    continue;
                }

                double centerX = adouble[mul];
                double centerY = adouble[mul + 1];
                double centerZ = adouble[mul + 2];
                double shiftedCenterX = centerX - 0.5D;
                double shiftedCenterY = centerY - 0.5D;
                double shiftedCenterZ = centerZ - 0.5D;

                int minY = Math.max(Math.max(Mth.ceil(shiftedCenterY - radius), pY), levelMinY);
                int maxY = Math.min(Math.min(Mth.floor(shiftedCenterY + radius), pY + pHeight - 1), levelMaxY);
                if (minY > maxY) {
                    continue;
                }

                double invRadius = 1.0 / radius;
                LevelChunkSection cachedSection = null;
                int[] cachedRaw = null;
                int lastSecX = Integer.MIN_VALUE;
                int lastSecY = Integer.MIN_VALUE;
                int lastSecZ = Integer.MIN_VALUE;

                for (int currY = minY; currY <= maxY; currY++) {
                    double dy = (currY - shiftedCenterY) * invRadius;
                    double dySq = dy * dy;
                    if (dySq >= 1.0) {
                        continue;
                    }

                    double zRadius = Math.sqrt(1.0 - dySq) * radius;
                    int minZ = Math.max(Mth.ceil(shiftedCenterZ - zRadius), pZ);
                    int maxZ = Math.min(Mth.floor(shiftedCenterZ + zRadius), pZ + pWidth - 1);
                    if (minZ > maxZ) {
                        continue;
                    }

                    int bitIndexY = (currY - pY) * pWidth;
                    int secY = currY >> 4;

                    for (int currZ = minZ; currZ <= maxZ; currZ++) {
                        double dz = (currZ - shiftedCenterZ) * invRadius;
                        double dyzSq = dySq + dz * dz;
                        if (dyzSq >= 1.0) {
                            continue;
                        }

                        double xRadius = Math.sqrt(1.0 - dyzSq) * radius;
                        int minX = Math.max(Mth.ceil(shiftedCenterX - xRadius), pX);
                        int maxX = Math.min(Mth.floor(shiftedCenterX + xRadius), pX + pWidth - 1);
                        if (minX > maxX) {
                            continue;
                        }

                        int bitIndexYZ = bitIndexY + (currZ - pZ) * planeStride;
                        int secZ = currZ >> 4;

                        for (int currX = minX; currX <= maxX; currX++) {
                            int bitIndex = (currX - pX) + bitIndexYZ;
                            if (bitset.get(bitIndex)) {
                                continue;
                            }
                            bitset.set(bitIndex);

                            int secX = currX >> 4;
                            if (secX != lastSecX || secY != lastSecY || secZ != lastSecZ) {
                                blockpos$mutableblockpos.set(currX, currY, currZ);
                                cachedSection = bulksectionaccess.getSection(blockpos$mutableblockpos);
                                cachedRaw = cachedSection == null ? null : LevelChunkSection$FlatBlockArray.rawData(cachedSection);
                                lastSecX = secX;
                                lastSecY = secY;
                                lastSecZ = secZ;
                            }

                            if (cachedSection == null) {
                                continue;
                            }

                            int localX = currX & 15;
                            int localY = currY & 15;
                            int localZ = currZ & 15;
                            int sectionIndex = (localY << 8) | (localZ << 4) | localX;

                            int currentStateId;
                            BlockState currentState = null;
                            Integer workspaceStateId = GAWorkspaceWriteBridge.readBlockIdCurrent(currX, currY, currZ);
                            if (workspaceStateId != null) {
                                currentStateId = workspaceStateId;
                            } else if (cachedRaw != null) {
                                currentStateId = cachedRaw[sectionIndex];
                            } else {
                                currentState = cachedSection.getBlockState(localX, localY, localZ);
                                currentStateId = GA$BlockStateExtension.get(currentState).bts$getFastId();
                            }

                            for (int t = 0; t < fastTargets.length; t++) {
                                FastTarget target = fastTargets[t];
                                boolean matched = target.matchesStateId(currentStateId);
                                if (!matched && target.requiresFallbackState()) {
                                    if (currentState == null) {
                                        currentState = states[currentStateId];
                                    }
                                    matched = target.fallbackRule().test(currentState, pRandom);
                                }

                                if (!matched) {
                                    continue;
                                }

                                blockpos$mutableblockpos.set(currX, currY, currZ);
                                if (bts$shouldSkipAirCheck(pRandom, airChance)
                                        || !bts$isAdjacentToAirUltraFast(
                                                bulksectionaccess,
                                                cachedSection,
                                                cachedRaw,
                                                airStates,
                                                currX,
                                                currY,
                                                currZ,
                                                localX,
                                                localY,
                                                localZ,
                                                sectionIndex,
                                                blockpos$mutableblockpos
                                        )) {
                                    this.bts$commitPlacement(
                                            pLevel,
                                            bulksectionaccess,
                                            cachedSection,
                                            cachedRaw,
                                            blockpos$mutableblockpos,
                                            target,
                                            localX,
                                            localY,
                                            localZ,
                                            sectionIndex,
                                            currentStateId,
                                            airStates,
                                            placementMayBeAir
                                    );
                                    placedCount++;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (adouble.length > BTS$MAX_RETAINED_VEIN_DATA_VALUES) {
            BTS$VEIN_DATA.set(new double[64 * 4]);
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
        Block[] validBlocks = BTS$RULE_CACHE.getOrCompute(rule, MixinOreFeature::bts$unwrapRule);

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
        }

        return bts$shouldSkipAirCheck(pRandom, pConfig.discardChanceOnAirExposure)
                || !fastIsAdjacentToAir(pAdjacentStateAccessor, pMutablePos);
    }

    @Unique
    private static Block[] bts$unwrapRule(RuleTest rule) {
        if (rule instanceof BlockMatchTest bmt) {
            return new Block[] {bmt.block};
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
    private static CompiledTargets bts$compileTargets(OreConfiguration config) {
        if (FastBlockStateCache.STATES == null) {
            FastBlockStateCache.init(dev.sixik.generator_accelerator.GeneratorAccelerator.platform);
        }

        DefaultedRegistry<Block> registry = BuiltInRegistries.BLOCK;
        int stateCount = FastBlockStateCache.STATES.length;
        FastTarget[] targets = new FastTarget[config.targetStates.size()];
        boolean hasFallbackTargets = false;
        boolean placementMayBeAir = false;

        for (int i = 0; i < config.targetStates.size(); i++) {
            OreConfiguration.TargetBlockState target = config.targetStates.get(i);
            RuleTest rule = target.target;
            int singleStateId = -1;
            int[] validStateIds = null;
            boolean[] validStateIdMask = null;
            RuleTest fallbackRule = null;
            int placementStateId = GA$BlockStateExtension.get(target.state).bts$getFastId();
            placementMayBeAir |= FastBlockStateCache.isAir(placementStateId);

            if (rule instanceof BlockMatchTest bmt) {
                StateSet stateSet = BTS$BLOCK_STATE_SET_CACHE.getOrCompute(
                        bmt.block,
                        block -> bts$collectStateIds(block, stateCount));
                singleStateId = stateSet.singleStateId();
                validStateIds = stateSet.validStateIds();
                validStateIdMask = stateSet.validStateIdMask();
            } else if (rule instanceof TagMatchTest tmt) {
                StateSet stateSet = BTS$TAG_STATE_SET_CACHE.getOrCompute(tmt.tag, tag -> {
                    Optional<HolderSet.Named<Block>> tagOpt = registry.getTag(tag);
                    return tagOpt.map(blocks -> bts$collectStateIds(blocks, stateCount))
                            .orElse(BTS$EMPTY_STATE_SET);
                });
                singleStateId = stateSet.singleStateId();
                validStateIds = stateSet.validStateIds();
                validStateIdMask = stateSet.validStateIdMask();
            } else {
                fallbackRule = rule;
                hasFallbackTargets = true;
            }

            targets[i] = new FastTarget(singleStateId, validStateIds, validStateIdMask, fallbackRule, target.state, placementStateId);
        }

        return new CompiledTargets(targets, hasFallbackTargets, placementMayBeAir);
    }

    @Unique
    private static StateSet bts$collectStateIds(Block block, int stateCount) {
        List<BlockState> possibleStates = block.getStateDefinition().getPossibleStates();
        int size = possibleStates.size();
        if (size == 1) {
            return new StateSet(GA$BlockStateExtension.get(possibleStates.getFirst()).bts$getFastId(), null, null);
        }

        IntArrayList ids = new IntArrayList(size);
        for (int i = 0; i < size; i++) {
            ids.add(GA$BlockStateExtension.get(possibleStates.get(i)).bts$getFastId());
        }
        return bts$packStateIds(ids, stateCount);
    }

    @Unique
    private static StateSet bts$collectStateIds(HolderSet.Named<Block> tagData, int stateCount) {
        ObjectArrayList<Holder<Block>> tagDataList = (ObjectArrayList<Holder<Block>>) tagData.contents();
        Object[] tagDataArray = tagDataList.elements();
        IntArrayList ids = new IntArrayList();

        for (int entry = 0; entry < tagDataList.size(); entry++) {
            Block block = ((Holder<Block>) tagDataArray[entry]).value();
            List<BlockState> states = block.getStateDefinition().getPossibleStates();
            for (int stateIndex = 0; stateIndex < states.size(); stateIndex++) {
                ids.add(GA$BlockStateExtension.get(states.get(stateIndex)).bts$getFastId());
            }
        }

        return bts$packStateIds(ids, stateCount);
    }

    @Unique
    private static StateSet bts$packStateIds(IntArrayList ids, int stateCount) {
        int size = ids.size();
        if (size == 0) {
            return new StateSet(-1, new int[0], null);
        }
        if (size == 1) {
            return new StateSet(ids.getInt(0), null, null);
        }
        if (size <= 4) {
            return new StateSet(-1, ids.toIntArray(), null);
        }

        boolean[] mask = new boolean[stateCount];
        for (int i = 0; i < size; i++) {
            int stateId = ids.getInt(i);
            if (stateId >= 0 && stateId < stateCount) {
                mask[stateId] = true;
            }
        }
        return new StateSet(-1, null, mask);
    }

    @Unique
    private static boolean bts$shouldSkipAirCheck(RandomSource random, float chance) {
        return chance <= 0.0F || (chance < 1.0F && random.nextFloat() >= chance);
    }

    @Unique
    private static boolean fastIsAdjacentToAir(Function<BlockPos, BlockState> pAdjacentStateAccessor, BlockPos.MutableBlockPos pPos) {
        int x = pPos.getX();
        int y = pPos.getY();
        int z = pPos.getZ();

        Vec3i[] dir = BTS$DIRECTIONS;
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

    @Unique
    private void bts$commitPlacement(
            WorldGenLevel level,
            BulkSectionAccess access,
            LevelChunkSection section,
            int[] raw,
            BlockPos.MutableBlockPos pos,
            FastTarget target,
            int localX,
            int localY,
            int localZ,
            int sectionIndex,
            int previousStateId,
            boolean[] airStates,
            boolean placementMayBeAir
    ) {
        BlockState placementState = target.placementState();
        if (GAWorkspaceWriteBridge.writeCurrentWorkspaceOnly(null, pos, placementState)) {
            return;
        }
        if (raw != null && (!placementMayBeAir || airStates[previousStateId] == airStates[target.placementStateId()])) {
            raw[sectionIndex] = target.placementStateId();
            return;
        }

        section.setBlockState(localX, localY, localZ, placementState, false);
    }

    @Unique
    private boolean bts$isAdjacentToAirUltraFast(
            BulkSectionAccess access,
            LevelChunkSection section,
            int[] raw,
            boolean[] airStates,
            int globalX,
            int globalY,
            int globalZ,
            int localX,
            int localY,
            int localZ,
            int sectionIndex,
            BlockPos.MutableBlockPos pos
    ) {
        if (GAWorkspaceWriteBridge.readBlockIdCurrent(globalX, globalY, globalZ) != null) {
            return bts$isAirAt(access, airStates, globalX + 1, globalY, globalZ, pos)
                    || bts$isAirAt(access, airStates, globalX - 1, globalY, globalZ, pos)
                    || bts$isAirAt(access, airStates, globalX, globalY + 1, globalZ, pos)
                    || bts$isAirAt(access, airStates, globalX, globalY - 1, globalZ, pos)
                    || bts$isAirAt(access, airStates, globalX, globalY, globalZ + 1, pos)
                    || bts$isAirAt(access, airStates, globalX, globalY, globalZ - 1, pos);
        }

        if (raw != null) {
            if (localX > 0) {
                if (airStates[raw[sectionIndex - 1]]) return true;
            } else if (bts$isAirAt(access, airStates, globalX - 1, globalY, globalZ, pos)) {
                return true;
            }

            if (localX < 15) {
                if (airStates[raw[sectionIndex + 1]]) return true;
            } else if (bts$isAirAt(access, airStates, globalX + 1, globalY, globalZ, pos)) {
                return true;
            }

            if (localY > 0) {
                if (airStates[raw[sectionIndex - 256]]) return true;
            } else if (bts$isAirAt(access, airStates, globalX, globalY - 1, globalZ, pos)) {
                return true;
            }

            if (localY < 15) {
                if (airStates[raw[sectionIndex + 256]]) return true;
            } else if (bts$isAirAt(access, airStates, globalX, globalY + 1, globalZ, pos)) {
                return true;
            }

            if (localZ > 0) {
                if (airStates[raw[sectionIndex - 16]]) return true;
            } else if (bts$isAirAt(access, airStates, globalX, globalY, globalZ - 1, pos)) {
                return true;
            }

            if (localZ < 15) {
                if (airStates[raw[sectionIndex + 16]]) return true;
            } else if (bts$isAirAt(access, airStates, globalX, globalY, globalZ + 1, pos)) {
                return true;
            }

            return false;
        }

        if (localX > 0 && localX < 15 && localY > 0 && localY < 15 && localZ > 0 && localZ < 15) {
            if (section.getBlockState(localX + 1, localY, localZ).isAir()) return true;
            if (section.getBlockState(localX - 1, localY, localZ).isAir()) return true;
            if (section.getBlockState(localX, localY + 1, localZ).isAir()) return true;
            if (section.getBlockState(localX, localY - 1, localZ).isAir()) return true;
            if (section.getBlockState(localX, localY, localZ + 1).isAir()) return true;
            if (section.getBlockState(localX, localY, localZ - 1).isAir()) return true;
            return false;
        }

        return bts$isAirAt(access, airStates, globalX + 1, globalY, globalZ, pos)
                || bts$isAirAt(access, airStates, globalX - 1, globalY, globalZ, pos)
                || bts$isAirAt(access, airStates, globalX, globalY + 1, globalZ, pos)
                || bts$isAirAt(access, airStates, globalX, globalY - 1, globalZ, pos)
                || bts$isAirAt(access, airStates, globalX, globalY, globalZ + 1, pos)
                || bts$isAirAt(access, airStates, globalX, globalY, globalZ - 1, pos);
    }

    @Unique
    private boolean bts$isAirAt(
            BulkSectionAccess access,
            boolean[] airStates,
            int globalX,
            int globalY,
            int globalZ,
            BlockPos.MutableBlockPos pos
    ) {
        Integer workspaceStateId = GAWorkspaceWriteBridge.readBlockIdCurrent(globalX, globalY, globalZ);
        if (workspaceStateId != null) {
            return airStates[workspaceStateId];
        }

        pos.set(globalX, globalY, globalZ);
        LevelChunkSection neighborSection = access.getSection(pos);
        if (neighborSection == null) {
            return true;
        }

        int[] neighborRaw = LevelChunkSection$FlatBlockArray.rawData(neighborSection);
        if (neighborRaw != null) {
            int index = ((globalY & 15) << 8) | ((globalZ & 15) << 4) | (globalX & 15);
            return airStates[neighborRaw[index]];
        }

        return access.getBlockState(pos).isAir();
    }

    @Unique
    private record CompiledTargets(FastTarget[] targets, boolean hasFallbackTargets, boolean placementMayBeAir) {
    }

    @Unique
    private record StateSet(int singleStateId, int[] validStateIds, boolean[] validStateIdMask) {
    }
}
