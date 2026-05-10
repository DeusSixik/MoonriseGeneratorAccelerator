package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.features.FastTarget;
import dev.sixik.generator_accelerator.common.features.cache.SharedWeakCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Optional;

@Mixin(value = ScatteredOreFeature.class, priority = 999)
public abstract class MixinScatteredOreFeature extends Feature<OreConfiguration> {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SHARED_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final SharedWeakCache<OreConfiguration, CompiledTargets> GA$TARGET_CACHE = new SharedWeakCache<>();

    private MixinScatteredOreFeature(Codec<OreConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Run scattered ore through the same state-id and raw-section path as the main ore feature.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<OreConfiguration> placeContext) {
        WorldGenLevel level = placeContext.level();
        RandomSource random = placeContext.random();
        OreConfiguration config = placeContext.config();
        BlockPos origin = placeContext.origin();
        BlockPos.MutableBlockPos mutablePos = GA$SHARED_POS.get();

        CompiledTargets compiledTargets = GA$TARGET_CACHE.getOrCompute(config, MixinScatteredOreFeature::ga$compileTargets);
        FastTarget[] fastTargets = compiledTargets.targets();
        boolean placementMayBeAir = compiledTargets.placementMayBeAir();
        BlockState[] states = FastBlockStateCache.STATES;
        if (states == null) {
            FastBlockStateCache.init(GeneratorAccelerator.platform);
            states = FastBlockStateCache.STATES;
        }
        boolean[] airStates = FastBlockStateCache.AIR_STATES;

        int count = random.nextInt(config.size + 1);
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();
        float airChance = config.discardChanceOnAirExposure;
        boolean placedAny = false;

        try (BulkSectionAccess access = new BulkSectionAccess(level)) {
            LevelChunkSection cachedSection = null;
            int[] cachedRaw = null;
            int lastSecX = Integer.MIN_VALUE;
            int lastSecY = Integer.MIN_VALUE;
            int lastSecZ = Integer.MIN_VALUE;

            for (int i = 0; i < count; i++) {
                int spread = Math.min(i, 7);
                mutablePos.set(
                        originX + ga$getSpread(random, spread),
                        originY + ga$getSpread(random, spread),
                        originZ + ga$getSpread(random, spread)
                );

                int secX = mutablePos.getX() >> 4;
                int secY = mutablePos.getY() >> 4;
                int secZ = mutablePos.getZ() >> 4;
                if (secX != lastSecX || secY != lastSecY || secZ != lastSecZ) {
                    cachedSection = access.getSection(mutablePos);
                    cachedRaw = cachedSection == null ? null : LevelChunkSection$FlatBlockArray.rawData(cachedSection);
                    lastSecX = secX;
                    lastSecY = secY;
                    lastSecZ = secZ;
                }

                if (cachedSection == null) {
                    continue;
                }

                int localX = mutablePos.getX() & 15;
                int localY = mutablePos.getY() & 15;
                int localZ = mutablePos.getZ() & 15;
                int sectionIndex = (localY << 8) | (localZ << 4) | localX;

                BlockState currentState = null;
                int currentStateId;
                if (cachedRaw != null) {
                    currentStateId = cachedRaw[sectionIndex];
                } else {
                    currentState = cachedSection.getBlockState(localX, localY, localZ);
                    currentStateId = GA$BlockStateExtension.get(currentState).bts$getFastId();
                }

                for (int targetIndex = 0; targetIndex < fastTargets.length; targetIndex++) {
                    FastTarget target = fastTargets[targetIndex];
                    boolean matched = target.matchesStateId(currentStateId);
                    if (!matched && target.requiresFallbackState()) {
                        if (currentState == null) {
                            currentState = states[currentStateId];
                        }
                        matched = target.fallbackRule().test(currentState, random);
                    }

                    if (!matched) {
                        continue;
                    }

                    if (ga$shouldSkipAirCheck(random, airChance)
                            || !ga$isAdjacentToAir(access, cachedSection, cachedRaw, airStates, mutablePos, localX, localY, localZ, sectionIndex)) {
                        ga$commitPlacement(cachedSection, cachedRaw, target, currentStateId, airStates, placementMayBeAir, localX, localY, localZ, sectionIndex);
                        placedAny = true;
                        break;
                    }
                }
            }
        }

        return placedAny;
    }

    @Unique
    private static int ga$getSpread(RandomSource random, int spread) {
        return Math.round((random.nextFloat() - random.nextFloat()) * (float) spread);
    }

    @Unique
    private static CompiledTargets ga$compileTargets(OreConfiguration config) {
        if (FastBlockStateCache.STATES == null) {
            FastBlockStateCache.init(GeneratorAccelerator.platform);
        }

        DefaultedRegistry<Block> registry = BuiltInRegistries.BLOCK;
        int stateCount = FastBlockStateCache.STATES.length;
        FastTarget[] targets = new FastTarget[config.targetStates.size()];
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
                StateSet stateSet = ga$collectStateIds(bmt.block, stateCount);
                singleStateId = stateSet.singleStateId();
                validStateIds = stateSet.validStateIds();
                validStateIdMask = stateSet.validStateIdMask();
            } else if (rule instanceof TagMatchTest tmt) {
                Optional<HolderSet.Named<Block>> tagOpt = registry.getTag(tmt.tag);
                if (tagOpt.isPresent()) {
                    StateSet stateSet = ga$collectStateIds(tagOpt.get(), stateCount);
                    singleStateId = stateSet.singleStateId();
                    validStateIds = stateSet.validStateIds();
                    validStateIdMask = stateSet.validStateIdMask();
                } else {
                    validStateIds = new int[0];
                }
            } else {
                fallbackRule = rule;
            }

            targets[i] = new FastTarget(singleStateId, validStateIds, validStateIdMask, fallbackRule, target.state, placementStateId);
        }

        return new CompiledTargets(targets, placementMayBeAir);
    }

    @Unique
    private static StateSet ga$collectStateIds(Block block, int stateCount) {
        List<BlockState> possibleStates = block.getStateDefinition().getPossibleStates();
        int size = possibleStates.size();
        if (size == 1) {
            return new StateSet(GA$BlockStateExtension.get(possibleStates.getFirst()).bts$getFastId(), null, null);
        }

        IntArrayList ids = new IntArrayList(size);
        for (int i = 0; i < size; i++) {
            ids.add(GA$BlockStateExtension.get(possibleStates.get(i)).bts$getFastId());
        }
        return ga$packStateIds(ids, stateCount);
    }

    @Unique
    private static StateSet ga$collectStateIds(HolderSet.Named<Block> tagData, int stateCount) {
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

        return ga$packStateIds(ids, stateCount);
    }

    @Unique
    private static StateSet ga$packStateIds(IntArrayList ids, int stateCount) {
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
    private static boolean ga$shouldSkipAirCheck(RandomSource random, float chance) {
        return chance <= 0.0F || (chance < 1.0F && random.nextFloat() >= chance);
    }

    @Unique
    private static void ga$commitPlacement(
            LevelChunkSection section,
            int[] raw,
            FastTarget target,
            int previousStateId,
            boolean[] airStates,
            boolean placementMayBeAir,
            int localX,
            int localY,
            int localZ,
            int sectionIndex
    ) {
        if (raw != null && (!placementMayBeAir || airStates[previousStateId] == airStates[target.placementStateId()])) {
            raw[sectionIndex] = target.placementStateId();
            return;
        }

        section.setBlockState(localX, localY, localZ, target.placementState(), false);
    }

    @Unique
    private static boolean ga$isAdjacentToAir(
            BulkSectionAccess access,
            LevelChunkSection section,
            int[] raw,
            boolean[] airStates,
            BlockPos.MutableBlockPos pos,
            int localX,
            int localY,
            int localZ,
            int sectionIndex
    ) {
        int globalX = pos.getX();
        int globalY = pos.getY();
        int globalZ = pos.getZ();

        if (raw != null) {
            if (localX > 0) {
                if (airStates[raw[sectionIndex - 1]]) return true;
            } else if (ga$isAirAt(access, airStates, globalX - 1, globalY, globalZ, pos)) {
                return true;
            }

            if (localX < 15) {
                if (airStates[raw[sectionIndex + 1]]) return true;
            } else if (ga$isAirAt(access, airStates, globalX + 1, globalY, globalZ, pos)) {
                return true;
            }

            if (localY > 0) {
                if (airStates[raw[sectionIndex - 256]]) return true;
            } else if (ga$isAirAt(access, airStates, globalX, globalY - 1, globalZ, pos)) {
                return true;
            }

            if (localY < 15) {
                if (airStates[raw[sectionIndex + 256]]) return true;
            } else if (ga$isAirAt(access, airStates, globalX, globalY + 1, globalZ, pos)) {
                return true;
            }

            if (localZ > 0) {
                if (airStates[raw[sectionIndex - 16]]) return true;
            } else if (ga$isAirAt(access, airStates, globalX, globalY, globalZ - 1, pos)) {
                return true;
            }

            if (localZ < 15) {
                if (airStates[raw[sectionIndex + 16]]) return true;
            } else if (ga$isAirAt(access, airStates, globalX, globalY, globalZ + 1, pos)) {
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

        return ga$isAirAt(access, airStates, globalX + 1, globalY, globalZ, pos)
                || ga$isAirAt(access, airStates, globalX - 1, globalY, globalZ, pos)
                || ga$isAirAt(access, airStates, globalX, globalY + 1, globalZ, pos)
                || ga$isAirAt(access, airStates, globalX, globalY - 1, globalZ, pos)
                || ga$isAirAt(access, airStates, globalX, globalY, globalZ + 1, pos)
                || ga$isAirAt(access, airStates, globalX, globalY, globalZ - 1, pos);
    }

    @Unique
    private static boolean ga$isAirAt(
            BulkSectionAccess access,
            boolean[] airStates,
            int globalX,
            int globalY,
            int globalZ,
            BlockPos.MutableBlockPos pos
    ) {
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
    private record CompiledTargets(FastTarget[] targets, boolean placementMayBeAir) {
    }

    @Unique
    private record StateSet(int singleStateId, int[] validStateIds, boolean[] validStateIdMask) {
    }
}
