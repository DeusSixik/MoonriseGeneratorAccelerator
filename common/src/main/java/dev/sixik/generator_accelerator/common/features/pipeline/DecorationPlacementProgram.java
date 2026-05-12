package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$HeightRangePlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$HeightmapPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementFilterAccess;
import dev.sixik.generator_accelerator.api.patches.GA$RandomOffsetPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$RepeatingPlacementAccess;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.features.ChunkAccess$getOrCreateHeightmapUnsynchronized;
import dev.sixik.generator_accelerator.common.features.FastTarget;
import dev.sixik.generator_accelerator.common.features.cache.SharedWeakCache;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import dev.sixik.generator_accelerator.common.features.pipeline.ore.OreTargetPlan;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkBehaviour;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.SculkSpreader;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.DripstoneClusterFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.feature.KelpFeature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.LargeDripstoneFeature;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;
import net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.PointedDripstoneFeature;
import net.minecraft.world.level.levelgen.feature.RandomPatchFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.SeagrassFeature;
import net.minecraft.world.level.levelgen.feature.SeaPickleFeature;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.VegetationPatchFeature;
import net.minecraft.world.level.levelgen.feature.WaterloggedVegetationPatchFeature;
import net.minecraft.world.level.levelgen.feature.configurations.CountConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SculkPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

final class DecorationPlacementProgram {
    private static final BlockState SEAGRASS_STATE = Blocks.SEAGRASS.defaultBlockState();
    private static final BlockState TALL_SEAGRASS_LOWER = Blocks.TALL_SEAGRASS.defaultBlockState();
    private static final BlockState TALL_SEAGRASS_UPPER = TALL_SEAGRASS_LOWER.setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER);
    private static final BlockState CAVE_AIR_STATE = Blocks.CAVE_AIR.defaultBlockState();
    private static final BlockState ICE_STATE = Blocks.ICE.defaultBlockState();
    private static final BlockState SNOW_STATE = Blocks.SNOW.defaultBlockState();
    private static final BlockState KELP_PLANT = Blocks.KELP_PLANT.defaultBlockState();
    private static final BlockState SCULK_CATALYST_STATE = Blocks.SCULK_CATALYST.defaultBlockState();
    private static final BlockState SCULK_SHRIEKER_SUMMON_STATE = Blocks.SCULK_SHRIEKER.defaultBlockState()
            .setValue(SculkShriekerBlock.CAN_SUMMON, true);
    private static final BlockState[] SEA_PICKLES = {
            Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 1),
            Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 2),
            Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 3),
            Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 4)
    };
    private static final BlockState[] KELP_HEADS = {
            Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 20),
            Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 21),
            Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 22),
            Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 23)
    };

    private static final int VANILLA_MODIFIER = 0;
    private static final int IN_SQUARE = 1;
    private static final int HEIGHT_RANGE = 2;
    private static final int HEIGHTMAP = 3;
    private static final int RANDOM_OFFSET = 4;
    private static final int REPEATING = 5;
    private static final int PLACEMENT_FILTER = 6;
    private static final int BIOME_FILTER = 7;
    private static final int FAST_POSITIONS = 8;
    private static final int GATE_NONE = 0;
    private static final int GATE_ORE = 1;
    private static final int GATE_PLANT = 2;
    private static final int GATE_WATER_PLANT = 3;
    private static final int GATE_TREE = 4;
    private static final int GATE_STONE_OR_DIRT = 5;
    private static final int GATE_CAVE_SOLID = 6;
    private static final int SIMPLE_BLOCK_BATCH_TRY_THRESHOLD = 24;
    private static final long NO_POSITION = Long.MIN_VALUE;
    private static final SharedWeakCache<BlockColumnConfiguration, CompiledBlockColumn> BLOCK_COLUMN_CACHE =
            new SharedWeakCache<>();
    private static final ThreadLocal<int[]> BLOCK_COLUMN_HEIGHTS =
            ThreadLocal.withInitial(() -> new int[8]);

    private final int[] opcodes;
    private final PlacementModifier[] modifiers;
    private final HeightProvider[] heightProviders;
    private final Heightmap.Types[] heightmapTypes;
    private final IntProvider[] randomOffsetXz;
    private final IntProvider[] randomOffsetY;
    private final GA$RepeatingPlacementAccess[] repeatingPlacements;
    private final GA$PlacementFilterAccess[] placementFilters;
    private final boolean hasVanillaModifier;
    private final boolean hasBiomeFilter;

    private DecorationPlacementProgram(
            int[] opcodes,
            PlacementModifier[] modifiers,
            HeightProvider[] heightProviders,
            Heightmap.Types[] heightmapTypes,
            IntProvider[] randomOffsetXz,
            IntProvider[] randomOffsetY,
            GA$RepeatingPlacementAccess[] repeatingPlacements,
            GA$PlacementFilterAccess[] placementFilters,
            boolean hasVanillaModifier,
            boolean hasBiomeFilter
    ) {
        this.opcodes = opcodes;
        this.modifiers = modifiers;
        this.heightProviders = heightProviders;
        this.heightmapTypes = heightmapTypes;
        this.randomOffsetXz = randomOffsetXz;
        this.randomOffsetY = randomOffsetY;
        this.repeatingPlacements = repeatingPlacements;
        this.placementFilters = placementFilters;
        this.hasVanillaModifier = hasVanillaModifier;
        this.hasBiomeFilter = hasBiomeFilter;
    }

    static DecorationPlacementProgram compile(PlacedFeature feature) {
        List<PlacementModifier> placement = feature.placement();
        int size = placement.size();
        int[] opcodes = new int[size];
        PlacementModifier[] modifiers = new PlacementModifier[size];
        HeightProvider[] heightProviders = new HeightProvider[size];
        Heightmap.Types[] heightmapTypes = new Heightmap.Types[size];
        IntProvider[] randomOffsetXz = new IntProvider[size];
        IntProvider[] randomOffsetY = new IntProvider[size];
        GA$RepeatingPlacementAccess[] repeatingPlacements = new GA$RepeatingPlacementAccess[size];
        GA$PlacementFilterAccess[] placementFilters = new GA$PlacementFilterAccess[size];
        boolean hasVanillaModifier = false;
        boolean hasBiomeFilter = false;

        for (int i = 0; i < size; i++) {
            PlacementModifier modifier = placement.get(i);
            modifiers[i] = modifier;
            int opcode = opcodeFor(modifier);
            opcodes[i] = opcode;
            if (opcode == BIOME_FILTER) {
                hasBiomeFilter = true;
            }
            switch (opcode) {
                case HEIGHT_RANGE -> heightProviders[i] = ((GA$HeightRangePlacementAccess) modifier).ga$heightProvider();
                case HEIGHTMAP -> heightmapTypes[i] = ((GA$HeightmapPlacementAccess) modifier).ga$heightmapType();
                case RANDOM_OFFSET -> {
                    GA$RandomOffsetPlacementAccess access = (GA$RandomOffsetPlacementAccess) modifier;
                    randomOffsetXz[i] = access.ga$xzSpread();
                    randomOffsetY[i] = access.ga$ySpread();
                }
                case REPEATING -> repeatingPlacements[i] = (GA$RepeatingPlacementAccess) modifier;
                case PLACEMENT_FILTER -> placementFilters[i] = (GA$PlacementFilterAccess) modifier;
                case VANILLA_MODIFIER -> hasVanillaModifier = true;
                default -> {
                }
            }
        }

        return new DecorationPlacementProgram(
                opcodes,
                modifiers,
                heightProviders,
                heightmapTypes,
                randomOffsetXz,
                randomOffsetY,
                repeatingPlacements,
                placementFilters,
                hasVanillaModifier,
                hasBiomeFilter
        );
    }

    boolean hasVanillaModifier() {
        return this.hasVanillaModifier;
    }

    boolean hasBiomeFilter() {
        return this.hasBiomeFilter;
    }

    boolean isIdentity() {
        return this.opcodes.length == 0;
    }

    boolean execute(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext
    ) {
        Holder<ConfiguredFeature<?, ?>> holder = kernel.configuredFeature();
        if (holder == null) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.FALLBACK_VANILLA_CALLS);
            return false;
        }
        return executeConfigured(kernel, context, scratch, placementContext, holder.value(), context.originX(), context.originY(), context.originZ());
    }

    boolean executeConfigured(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            ConfiguredFeature<?, ?> configuredFeature,
            int x,
            int y,
            int z
    ) {
        return executeAt(kernel, context, scratch, placementContext, configuredFeature, 0, x, y, z);
    }

    boolean executeSimpleBlockFused(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            SimpleBlockConfiguration simpleBlockConfiguration,
            int x,
            int y,
            int z
    ) {
        return executeSimpleBlockFused(kernel, context, scratch, placementContext, kernel.fallbackFeature(), simpleBlockConfiguration, x, y, z);
    }

    private boolean executeSimpleBlockFused(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            PlacedFeature biomeFilterFeature,
            SimpleBlockConfiguration simpleBlockConfiguration,
            int x,
            int y,
            int z
    ) {
        return executeFusedAt(kernel, context, scratch, placementContext, biomeFilterFeature, 0, x, y, z, simpleBlockConfiguration, null, null, SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK);
    }

    boolean executeRandomPatchSimpleFused(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            RandomPatchConfiguration randomPatchConfiguration,
            int x,
            int y,
            int z
    ) {
        return executeFusedAt(kernel, context, scratch, placementContext, kernel.fallbackFeature(), 0, x, y, z, null, randomPatchConfiguration, null, SelectorPlan.FAST_BRANCH_RANDOM_PATCH_SIMPLE);
    }

    boolean executeRandomPatchSelectorFused(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            RandomPatchConfiguration randomPatchConfiguration,
            int x,
            int y,
            int z
    ) {
        return executeFusedAt(kernel, context, scratch, placementContext, kernel.fallbackFeature(), 0, x, y, z, null, randomPatchConfiguration, null, SelectorPlan.FAST_BRANCH_RANDOM_PATCH_SELECTOR);
    }

    boolean executeSelectorFused(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            SelectorPlan selectorPlan,
            int x,
            int y,
            int z
    ) {
        return executeSelectorFused(kernel, context, scratch, placementContext, kernel.fallbackFeature(), selectorPlan, x, y, z);
    }

    private boolean executeSelectorFused(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            PlacedFeature biomeFilterFeature,
            SelectorPlan selectorPlan,
            int x,
            int y,
            int z
    ) {
        return executeFusedAt(kernel, context, scratch, placementContext, biomeFilterFeature, 0, x, y, z, null, null, selectorPlan, SelectorPlan.FAST_BRANCH_SELECTOR);
    }

    private boolean executeAt(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            ConfiguredFeature<?, ?> configuredFeature,
            int opIndex,
            int x,
            int y,
            int z
    ) {
        if (opIndex >= this.opcodes.length) {
            return placeConfigured(kernel, configuredFeature, context, scratch, placementContext, x, y, z);
        }

        return switch (this.opcodes[opIndex]) {
            case IN_SQUARE -> executeAt(kernel, context, scratch, placementContext, configuredFeature, opIndex + 1, x + context.random().nextInt(16), y, z + context.random().nextInt(16));
            case HEIGHT_RANGE -> executeAt(kernel, context, scratch, placementContext, configuredFeature, opIndex + 1, x, this.heightProviders[opIndex].sample(context.random(), placementContext), z);
            case HEIGHTMAP -> {
                int height = fastHeight(context, scratch, this.heightmapTypes[opIndex], x, z);
                if (height <= context.level().getMinBuildHeight()) {
                    yield false;
                }
                yield executeAt(kernel, context, scratch, placementContext, configuredFeature, opIndex + 1, x, height, z);
            }
            case RANDOM_OFFSET -> executeAt(
                    kernel,
                    context,
                    scratch,
                    placementContext,
                    configuredFeature,
                    opIndex + 1,
                    x + this.randomOffsetXz[opIndex].sample(context.random()),
                    y + this.randomOffsetY[opIndex].sample(context.random()),
                    z + this.randomOffsetXz[opIndex].sample(context.random())
            );
            case REPEATING -> executeRepeating(kernel, context, scratch, placementContext, configuredFeature, opIndex, x, y, z);
            case PLACEMENT_FILTER -> this.placementFilters[opIndex].ga$shouldPlaceRaw(placementContext, context.random(), x, y, z, scratch.mutablePos)
                    && executeAt(kernel, context, scratch, placementContext, configuredFeature, opIndex + 1, x, y, z);
            case BIOME_FILTER -> passesBiomeFilter(kernel.fallbackFeature(), context, scratch, x, y, z)
                    && executeAt(kernel, context, scratch, placementContext, configuredFeature, opIndex + 1, x, y, z);
            case FAST_POSITIONS -> executeFastModifier(kernel, context, scratch, placementContext, configuredFeature, opIndex, x, y, z);
            default -> executeVanillaModifier(kernel, context, scratch, placementContext, configuredFeature, opIndex, x, y, z);
        };
    }

    private boolean executeRepeating(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            ConfiguredFeature<?, ?> configuredFeature,
            int opIndex,
            int x,
            int y,
            int z
    ) {
        int count = this.repeatingPlacements[opIndex].ga$repeatingCount(context.random(), scratch.mutablePos.set(x, y, z));
        boolean success = false;
        for (int i = 0; i < count; i++) {
            if (executeAt(kernel, context, scratch, placementContext, configuredFeature, opIndex + 1, x, y, z)) {
                success = true;
            }
        }
        return success;
    }

    private boolean executeVanillaModifier(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            ConfiguredFeature<?, ?> configuredFeature,
            int opIndex,
            int x,
            int y,
            int z
    ) {
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SLOW_PATH_OBJECT_ALLOCATING_CALLS);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SLOW_PATH_GENERIC_COLLECTION_CALLS);
        boolean success = false;
        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(x, y, z);
        try (Stream<BlockPos> positions = this.modifiers[opIndex].getPositions(placementContext, context.random(), pos)) {
            Iterator<BlockPos> iterator = positions.iterator();
            while (iterator.hasNext()) {
                BlockPos next = iterator.next();
                if (executeAt(kernel, context, scratch, placementContext, configuredFeature, opIndex + 1, next.getX(), next.getY(), next.getZ())) {
                    success = true;
                }
            }
        }
        return success;
    }

    private boolean executeFastModifier(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            ConfiguredFeature<?, ?> configuredFeature,
            int opIndex,
            int x,
            int y,
            int z
    ) {
        LongScratchBuffer positions = scratch.acquireModifierPositionBuffer();
        try {
            GA$PlacementModifierExtension.get(this.modifiers[opIndex]).generatePositionsRaw(
                    placementContext,
                    context.random(),
                    BlockPos.asLong(x, y, z),
                    positions
            );

            boolean success = false;
            long[] values = positions.elements();
            for (int i = 0, size = positions.size(); i < size; i++) {
                long packedPos = values[i];
                if (executeAt(
                        kernel,
                        context,
                        scratch,
                        placementContext,
                        configuredFeature,
                        opIndex + 1,
                        BlockPos.getX(packedPos),
                        BlockPos.getY(packedPos),
                        BlockPos.getZ(packedPos)
                )) {
                    success = true;
                }
            }
            return success;
        } finally {
            scratch.releaseModifierPositionBuffer();
        }
    }

    private boolean executeFusedAt(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            PlacedFeature biomeFilterFeature,
            int opIndex,
            int x,
            int y,
            int z,
            SimpleBlockConfiguration simpleBlockConfiguration,
            RandomPatchConfiguration randomPatchConfiguration,
            SelectorPlan selectorPlan,
            int fastMode
    ) {
        if (opIndex >= this.opcodes.length) {
            return executeFusedLeaf(
                    kernel,
                    context,
                    scratch,
                    placementContext,
                    x,
                    y,
                    z,
                    simpleBlockConfiguration,
                    randomPatchConfiguration,
                    selectorPlan,
                    fastMode
            );
        }

        return switch (this.opcodes[opIndex]) {
            case IN_SQUARE -> executeFusedAt(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex + 1, x + context.random().nextInt(16), y, z + context.random().nextInt(16), simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode);
            case HEIGHT_RANGE -> executeFusedAt(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex + 1, x, this.heightProviders[opIndex].sample(context.random(), placementContext), z, simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode);
            case HEIGHTMAP -> {
                int height = fastHeight(context, scratch, this.heightmapTypes[opIndex], x, z);
                if (height <= context.level().getMinBuildHeight()) {
                    yield false;
                }
                yield executeFusedAt(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex + 1, x, height, z, simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode);
            }
            case RANDOM_OFFSET -> executeFusedAt(
                    kernel,
                    context,
                    scratch,
                    placementContext,
                    biomeFilterFeature,
                    opIndex + 1,
                    x + this.randomOffsetXz[opIndex].sample(context.random()),
                    y + this.randomOffsetY[opIndex].sample(context.random()),
                    z + this.randomOffsetXz[opIndex].sample(context.random()),
                    simpleBlockConfiguration,
                    randomPatchConfiguration,
                    selectorPlan,
                    fastMode
            );
            case REPEATING -> executeFusedRepeating(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex, x, y, z, simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode);
            case PLACEMENT_FILTER -> this.placementFilters[opIndex].ga$shouldPlaceRaw(placementContext, context.random(), x, y, z, scratch.mutablePos)
                    && executeFusedAt(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex + 1, x, y, z, simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode);
            case BIOME_FILTER -> passesBiomeFilter(biomeFilterFeature, context, scratch, x, y, z)
                    && executeFusedAt(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex + 1, x, y, z, simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode);
            case FAST_POSITIONS -> executeFusedFastModifier(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex, x, y, z, simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode);
            default -> executeFusedVanillaModifier(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex, x, y, z, simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode);
        };
    }

    private boolean executeFusedRepeating(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            PlacedFeature biomeFilterFeature,
            int opIndex,
            int x,
            int y,
            int z,
            SimpleBlockConfiguration simpleBlockConfiguration,
            RandomPatchConfiguration randomPatchConfiguration,
            SelectorPlan selectorPlan,
            int fastMode
    ) {
        int count = this.repeatingPlacements[opIndex].ga$repeatingCount(context.random(), scratch.mutablePos.set(x, y, z));
        boolean success = false;
        for (int i = 0; i < count; i++) {
            if (executeFusedAt(kernel, context, scratch, placementContext, biomeFilterFeature, opIndex + 1, x, y, z, simpleBlockConfiguration, randomPatchConfiguration, selectorPlan, fastMode)) {
                success = true;
            }
        }
        return success;
    }

    private boolean executeFusedFastModifier(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            PlacedFeature biomeFilterFeature,
            int opIndex,
            int x,
            int y,
            int z,
            SimpleBlockConfiguration simpleBlockConfiguration,
            RandomPatchConfiguration randomPatchConfiguration,
            SelectorPlan selectorPlan,
            int fastMode
    ) {
        LongScratchBuffer positions = scratch.acquireModifierPositionBuffer();
        try {
            GA$PlacementModifierExtension.get(this.modifiers[opIndex]).generatePositionsRaw(
                    placementContext,
                    context.random(),
                    BlockPos.asLong(x, y, z),
                    positions
            );

            boolean success = false;
            long[] values = positions.elements();
            for (int i = 0, size = positions.size(); i < size; i++) {
                long packedPos = values[i];
                if (executeFusedAt(
                        kernel,
                        context,
                        scratch,
                        placementContext,
                        biomeFilterFeature,
                        opIndex + 1,
                        BlockPos.getX(packedPos),
                        BlockPos.getY(packedPos),
                        BlockPos.getZ(packedPos),
                        simpleBlockConfiguration,
                        randomPatchConfiguration,
                        selectorPlan,
                        fastMode
                )) {
                    success = true;
                }
            }
            return success;
        } finally {
            scratch.releaseModifierPositionBuffer();
        }
    }

    private boolean executeFusedVanillaModifier(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            PlacedFeature biomeFilterFeature,
            int opIndex,
            int x,
            int y,
            int z,
            SimpleBlockConfiguration simpleBlockConfiguration,
            RandomPatchConfiguration randomPatchConfiguration,
            SelectorPlan selectorPlan,
            int fastMode
    ) {
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SLOW_PATH_OBJECT_ALLOCATING_CALLS);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SLOW_PATH_GENERIC_COLLECTION_CALLS);
        boolean success = false;
        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(x, y, z);
        try (Stream<BlockPos> positions = this.modifiers[opIndex].getPositions(placementContext, context.random(), pos)) {
            Iterator<BlockPos> iterator = positions.iterator();
            while (iterator.hasNext()) {
                BlockPos next = iterator.next();
                if (executeFusedAt(
                        kernel,
                        context,
                        scratch,
                        placementContext,
                        biomeFilterFeature,
                        opIndex + 1,
                        next.getX(),
                        next.getY(),
                        next.getZ(),
                        simpleBlockConfiguration,
                        randomPatchConfiguration,
                        selectorPlan,
                        fastMode
                )) {
                    success = true;
                }
            }
        }
        return success;
    }

    private static boolean executeFusedLeaf(
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z,
            SimpleBlockConfiguration simpleBlockConfiguration,
            RandomPatchConfiguration randomPatchConfiguration,
            SelectorPlan selectorPlan,
            int fastMode
    ) {
        return switch (fastMode) {
            case SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK -> simpleBlockConfiguration != null
                    && placeSimpleBlockNative(simpleBlockConfiguration, context, scratch, x, y, z);
            case SelectorPlan.FAST_BRANCH_RANDOM_PATCH_SIMPLE -> {
                Boolean placed = randomPatchConfiguration == null
                        ? null
                        : tryPlaceRandomPatchSimpleBlockNative(kernel, randomPatchConfiguration, context, scratch, placementContext, x, y, z);
                yield placed != null && placed;
            }
            case SelectorPlan.FAST_BRANCH_RANDOM_PATCH_SELECTOR -> {
                Boolean placed = randomPatchConfiguration == null
                        ? null
                        : tryPlaceRandomPatchSelectorNative(kernel, randomPatchConfiguration, context, scratch, placementContext, x, y, z);
                yield placed != null && placed;
            }
            case SelectorPlan.FAST_BRANCH_SELECTOR -> selectorPlan != null
                    && executeFusedSelectorAny(selectorPlan, context, scratch, placementContext, x, y, z);
            default -> false;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean placeConfigured(
            DecorationKernelPlan kernel,
            ConfiguredFeature<?, ?> configuredFeature,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(x, y, z);
        FeatureConfiguration config = configuredFeature.config();
        Feature feature = configuredFeature.feature();
        if (kernel.kind() == DecorationKernelKind.NATIVE_ORE
                && feature == Feature.ORE
                && config instanceof OreConfiguration oreConfiguration) {
            OreTargetPlan oreTargetPlan = kernel.oreTargetPlan();
            if (oreTargetPlan != null) {
                if (!passesDescriptorGate(feature, scratch, x, y, z)) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
                    return false;
                }
                if (!context.level().ensureCanWrite(pos)) {
                    return false;
                }
                return placeOreNative(oreConfiguration, oreTargetPlan, context, scratch, x, y, z);
            }
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_SCATTERED_ORE
                && feature == Feature.SCATTERED_ORE
                && config instanceof OreConfiguration oreConfiguration) {
            OreTargetPlan oreTargetPlan = kernel.oreTargetPlan();
            if (oreTargetPlan != null) {
                if (!passesDescriptorGate(feature, scratch, x, y, z)) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
                    return false;
                }
                if (!context.level().ensureCanWrite(pos)) {
                    return false;
                }
                return placeScatteredOreNative(oreConfiguration, oreTargetPlan, context, scratch, x, y, z);
            }
        }
        if (kernel.kind() == DecorationKernelKind.PARTIAL_NATIVE_DESCRIPTOR_GATED) {
            if (!passesDescriptorGate(feature, scratch, x, y, z)) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.PARTIAL_NATIVE_DESCRIPTOR_REJECTED_CALLS);
                return false;
            }
        }
        if (!context.level().ensureCanWrite(pos)) {
            return false;
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE && config instanceof RandomPatchConfiguration randomPatchConfiguration) {
            Boolean placed = tryPlaceRandomPatchSimpleBlockNative(kernel, randomPatchConfiguration, context, scratch, placementContext, x, y, z);
            if (placed != null) {
                return placed;
            }
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR && config instanceof RandomPatchConfiguration randomPatchConfiguration) {
            Boolean placed = tryPlaceRandomPatchSelectorNative(kernel, randomPatchConfiguration, context, scratch, placementContext, x, y, z);
            if (placed != null) {
                return placed;
            }
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_PLANT_WATER) {
            Boolean placed = tryPlaceWaterPlantNative(feature, config, context, scratch, x, y, z);
            if (placed != null) {
                return placed;
            }
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_SPRING
                && feature == Feature.SPRING
                && config instanceof SpringConfiguration springConfiguration) {
            return placeSpringNative(springConfiguration, context, scratch, x, y, z);
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_DISK
                && feature == Feature.DISK
                && config instanceof DiskConfiguration diskConfiguration) {
            return placeDiskNative(diskConfiguration, context, scratch, x, y, z);
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_BLOCK_COLUMN
                && feature == Feature.BLOCK_COLUMN
                && config instanceof BlockColumnConfiguration blockColumnConfiguration) {
            return placeBlockColumnNative(blockColumnConfiguration, context, scratch, x, y, z);
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_SNOW_FREEZE
                && feature == Feature.FREEZE_TOP_LAYER) {
            return placeSnowAndFreezeNative(context, scratch, x, z);
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_LAKE
                && feature == Feature.LAKE
                && config instanceof LakeFeature.Configuration lakeConfiguration) {
            return placeLakeNative(lakeConfiguration, context, scratch, x, y, z);
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_SCULK_PATCH
                && feature == Feature.SCULK_PATCH
                && config instanceof SculkPatchConfiguration sculkPatchConfiguration) {
            return placeSculkPatchNative(sculkPatchConfiguration, context, scratch, x, y, z);
        }
        if (kernel.kind() == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR
                || kernel.kind() == DecorationKernelKind.NATIVE_SELECTOR_SIMPLE) {
            Boolean placed = tryPlaceSelectorNative(kernel, configuredFeature, context, scratch, placementContext, x, y, z);
            if (placed != null) {
                return placed;
            }
        }
        if ((kernel.kind() == DecorationKernelKind.NATIVE_SIMPLE_BLOCK
                || kernel.kind() == DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE
                || kernel.kind() == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR
                || kernel.kind() == DecorationKernelKind.NATIVE_SELECTOR_SIMPLE)
                && config instanceof SimpleBlockConfiguration simpleBlockConfiguration) {
            return placeSimpleBlockNative(simpleBlockConfiguration, context, scratch, x, y, z);
        }
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.PARTIAL_NATIVE_OPTIMIZED_PLACEMENT_CALLS);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.FALLBACK_VANILLA_CALLS);
        boolean placed = feature.place(scratch.featurePlaceContext.set(context.level(), context.generator(), context.random(), pos, config));
        if (placed) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
        }
        return placed;
    }

    private static Boolean tryPlaceRandomPatchSimpleBlockNative(
            DecorationKernelPlan kernel,
            RandomPatchConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        ConfiguredFeature<?, ?> nestedConfigured = kernel.nestedConfiguredFeature();
        DecorationPlacementProgram nestedProgram = kernel.nestedPlacementProgram();
        PlacedFeature nestedPlacedFeature = config.feature().value();
        if (nestedConfigured == null || nestedProgram == null || nestedPlacedFeature == null) {
            return null;
        }
        if (nestedConfigured.feature() != Feature.SIMPLE_BLOCK || !(nestedConfigured.config() instanceof SimpleBlockConfiguration simpleBlockConfiguration)) {
            return null;
        }

        int spreadXZ = config.xzSpread() + 1;
        int spreadY = config.ySpread() + 1;
        int tries = config.tries();
        boolean useBatch = tries >= SIMPLE_BLOCK_BATCH_TRY_THRESHOLD && !scratch.isCollectingWriteJournal();
        long candidateStart = DecorationPipelineMetrics.startTimer();
        Optional<PlacedFeature> previousTopFeature = placementContext.topFeature();
        placementContext.clearTopFeature();
        if (useBatch) {
            scratch.beginSimpleBlockBatch();
        }
        boolean placedAny = false;
        try {
            for (int i = 0; i < tries; i++) {
                int candidateX = x + context.random().nextInt(spreadXZ) - context.random().nextInt(spreadXZ);
                int candidateY = y + context.random().nextInt(spreadY) - context.random().nextInt(spreadY);
                int candidateZ = z + context.random().nextInt(spreadXZ) - context.random().nextInt(spreadXZ);
                boolean placed = nestedProgram.isIdentity()
                        ? placeSimpleBlockNative(simpleBlockConfiguration, context, scratch, candidateX, candidateY, candidateZ)
                        : nestedProgram.executeSimpleBlockFused(kernel, context, scratch, placementContext, nestedPlacedFeature, simpleBlockConfiguration, candidateX, candidateY, candidateZ);
                if (placed) {
                    placedAny = true;
                }
            }
        } finally {
            placementContext.set(context.level(), context.generator(), previousTopFeature, activeDescriptors(context, scratch), context.workspace());
            DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_CANDIDATE_NANOS, candidateStart);
        }
        return useBatch ? flushWriteJournal(context, scratch) : placedAny;
    }

    private static Boolean tryPlaceRandomPatchSelectorNative(
            DecorationKernelPlan kernel,
            RandomPatchConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        ConfiguredFeature<?, ?> nestedConfigured = kernel.nestedConfiguredFeature();
        DecorationPlacementProgram nestedProgram = kernel.nestedPlacementProgram();
        SelectorPlan selectorPlan = kernel.selectorPlan();
        PlacedFeature nestedPlacedFeature = config.feature().value();
        if (nestedConfigured == null || nestedProgram == null || selectorPlan == null || nestedPlacedFeature == null) {
            return null;
        }
        return tryPlaceRandomPatchFusedSimpleSelectorNative(
                kernel,
                nestedProgram,
                nestedPlacedFeature,
                selectorPlan,
                config,
                context,
                scratch,
                placementContext,
                x,
                y,
                z
        );
    }

    private static Boolean tryPlaceRandomPatchFusedSimpleSelectorNative(
            DecorationKernelPlan kernel,
            DecorationPlacementProgram nestedProgram,
            PlacedFeature nestedPlacedFeature,
            SelectorPlan selectorPlan,
            RandomPatchConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        int spreadXZ = config.xzSpread() + 1;
        int spreadY = config.ySpread() + 1;
        int tries = config.tries();
        boolean useBatch = tries >= SIMPLE_BLOCK_BATCH_TRY_THRESHOLD && !scratch.isCollectingWriteJournal();
        long candidateStart = DecorationPipelineMetrics.startTimer();
        Optional<PlacedFeature> previousTopFeature = placementContext.topFeature();
        placementContext.clearTopFeature();
        if (useBatch) {
            scratch.beginSimpleBlockBatch();
        }
        boolean placedAny = false;
        try {
            for (int i = 0; i < tries; i++) {
                int candidateX = x + context.random().nextInt(spreadXZ) - context.random().nextInt(spreadXZ);
                int candidateY = y + context.random().nextInt(spreadY) - context.random().nextInt(spreadY);
                int candidateZ = z + context.random().nextInt(spreadXZ) - context.random().nextInt(spreadXZ);
                boolean placed = nestedProgram.isIdentity()
                        ? executeFusedSelectorAny(selectorPlan, context, scratch, placementContext, candidateX, candidateY, candidateZ)
                        : nestedProgram.executeSelectorFused(kernel, context, scratch, placementContext, nestedPlacedFeature, selectorPlan, candidateX, candidateY, candidateZ);
                if (placed) {
                    placedAny = true;
                }
            }
        } finally {
            placementContext.set(context.level(), context.generator(), previousTopFeature, activeDescriptors(context, scratch), context.workspace());
            DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_CANDIDATE_NANOS, candidateStart);
        }
        return useBatch ? flushWriteJournal(context, scratch) : placedAny;
    }

    private static Boolean tryPlaceSelectorNative(
            DecorationKernelPlan kernel,
            ConfiguredFeature<?, ?> configuredFeature,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        SelectorPlan selectorPlan = kernel.selectorPlan();
        if (selectorPlan == null) {
            return null;
        }

        return executeFusedSelectorAny(selectorPlan, context, scratch, placementContext, x, y, z);
    }

    private static boolean executeFusedSelectorAny(
            SelectorPlan selectorPlan,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        int branchIndex = selectSelectorBranch(selectorPlan, context.random());
        return executeFusedSelectorBranch(selectorPlan, branchIndex, context, scratch, placementContext, x, y, z);
    }

    private static boolean executeFusedSelectorBranch(
            SelectorPlan selectorPlan,
            int branchIndex,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        if (branchIndex < 0 || branchIndex >= selectorPlan.branchKernels().length) {
            return false;
        }
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SELECTOR_FUSED_PLACEMENT_CALLS);
        int fastMode = selectorPlan.branchFastModes()[branchIndex];
        DecorationKernelPlan branchKernel = selectorPlan.branchKernels()[branchIndex];
        DecorationPlacementProgram branchProgram = selectorPlan.branchPlacementPrograms()[branchIndex];
        ConfiguredFeature<?, ?> branchConfiguredFeature = selectorPlan.branchConfiguredFeatures()[branchIndex];
        if (branchKernel == null || branchProgram == null) {
            return false;
        }
        if (fastMode != SelectorPlan.FAST_BRANCH_NONE) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SELECTOR_FUSED_SIMPLE_CALLS);
            return switch (fastMode) {
                case SelectorPlan.FAST_BRANCH_SIMPLE_BLOCK -> {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SELECTOR_FUSED_FAST_SIMPLE_CALLS);
                    SimpleBlockConfiguration simpleConfig = selectorPlan.branchSimpleBlockConfigurations()[branchIndex];
                    yield simpleConfig != null && branchProgram.executeSimpleBlockFused(branchKernel, context, scratch, placementContext, simpleConfig, x, y, z);
                }
                case SelectorPlan.FAST_BRANCH_RANDOM_PATCH_SIMPLE -> {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SELECTOR_FUSED_FAST_RANDOM_PATCH_SIMPLE_CALLS);
                    RandomPatchConfiguration randomPatchConfig = selectorPlan.branchRandomPatchConfigurations()[branchIndex];
                    yield randomPatchConfig != null && branchProgram.executeRandomPatchSimpleFused(branchKernel, context, scratch, placementContext, randomPatchConfig, x, y, z);
                }
                case SelectorPlan.FAST_BRANCH_RANDOM_PATCH_SELECTOR -> {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SELECTOR_FUSED_FAST_RANDOM_PATCH_SELECTOR_CALLS);
                    RandomPatchConfiguration randomPatchConfig = selectorPlan.branchRandomPatchConfigurations()[branchIndex];
                    yield randomPatchConfig != null && branchProgram.executeRandomPatchSelectorFused(branchKernel, context, scratch, placementContext, randomPatchConfig, x, y, z);
                }
                case SelectorPlan.FAST_BRANCH_SELECTOR -> {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SELECTOR_FUSED_FAST_SELECTOR_CALLS);
                    SelectorPlan nestedSelectorPlan = branchKernel.selectorPlan();
                    yield nestedSelectorPlan != null && branchProgram.executeSelectorFused(branchKernel, context, scratch, placementContext, nestedSelectorPlan, x, y, z);
                }
                default -> false;
            };
        }

        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SELECTOR_FUSED_GENERIC_CALLS);
        int descriptorGate = selectorPlan.branchDescriptorGates()[branchIndex];
        if (descriptorGate != GATE_NONE
                && branchConfiguredFeature != null
                && !passesDescriptorGate(descriptorGate, branchConfiguredFeature.feature(), scratch, x, y, z)) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.SELECTOR_FUSED_DESCRIPTOR_REJECTS);
            return false;
        }
        if (branchConfiguredFeature == null) {
            return false;
        }
        noteSelectorFallbackReason(branchKernel);
        return branchProgram.executeConfigured(branchKernel, context, scratch, placementContext, branchConfiguredFeature, x, y, z);
    }

    private static int selectSelectorBranch(SelectorPlan selectorPlan, RandomSource random) {
        return switch (selectorPlan.mode()) {
            case SelectorPlan.MODE_RANDOM_FEATURE -> {
                DecorationKernelPlan[] branchKernels = selectorPlan.branchKernels();
                float[] chances = selectorPlan.branchChances();
                int branchIndex = branchKernels.length - 1;
                for (int i = 0; i < chances.length; i++) {
                    if (random.nextFloat() < chances[i]) {
                        branchIndex = i;
                        break;
                    }
                }
                yield branchIndex;
            }
            case SelectorPlan.MODE_RANDOM_BOOLEAN -> random.nextBoolean() ? 0 : 1;
            case SelectorPlan.MODE_SIMPLE_RANDOM -> random.nextInt(selectorPlan.branchKernels().length);
            default -> 0;
        };
    }

    private static boolean placeScatteredOreNative(
            OreConfiguration config,
            OreTargetPlan targetPlan,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int originX,
            int originY,
            int originZ
    ) {
        // Native scattered ore avoids FeaturePlaceContext plus repeated virtual feature dispatch.
        FastTarget[] targets = targetPlan.targets();
        BlockState[] states = fastStateCache();
        boolean[] airStates = FastBlockStateCache.AIR_STATES;
        RandomSource random = context.random();
        int count = random.nextInt(config.size + 1);
        float airChance = config.discardChanceOnAirExposure;
        boolean placedAny = false;

        try (BulkSectionAccess access = new BulkSectionAccess(context.level())) {
            BlockPos.MutableBlockPos pos = scratch.mutablePos;
            BlockPos.MutableBlockPos tempPos = scratch.secondMutablePos;
            LevelChunkSection cachedSection = null;
            int[] cachedRaw = null;
            int lastSecX = Integer.MIN_VALUE;
            int lastSecY = Integer.MIN_VALUE;
            int lastSecZ = Integer.MIN_VALUE;

            for (int i = 0; i < count; i++) {
                int spread = Math.min(i, 7);
                int x = originX + scatteredOreSpread(random, spread);
                int y = originY + scatteredOreSpread(random, spread);
                int z = originZ + scatteredOreSpread(random, spread);
                if (DecorationPipelineMetrics.ENABLED) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_GENERATED);
                }

                int secX = x >> 4;
                int secY = y >> 4;
                int secZ = z >> 4;
                if (secX != lastSecX || secY != lastSecY || secZ != lastSecZ) {
                    pos.set(x, y, z);
                    cachedSection = access.getSection(pos);
                    cachedRaw = cachedSection == null ? null : LevelChunkSection$FlatBlockArray.rawData(cachedSection);
                    lastSecX = secX;
                    lastSecY = secY;
                    lastSecZ = secZ;
                    if (DecorationPipelineMetrics.ENABLED) {
                        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_SECTION_SWITCHES);
                    }
                }

                if (cachedSection == null) {
                    if (DecorationPipelineMetrics.ENABLED) {
                        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_KERNEL);
                    }
                    continue;
                }

                int localX = x & 15;
                int localY = y & 15;
                int localZ = z & 15;
                int sectionIndex = (localY << 8) | (localZ << 4) | localX;

                BlockState currentState = null;
                int currentStateId;
                if (cachedRaw != null) {
                    currentStateId = cachedRaw[sectionIndex];
                } else {
                    currentState = cachedSection.getBlockState(localX, localY, localZ);
                    currentStateId = GA$BlockStateExtension.get(currentState).bts$getFastId();
                }
                if (DecorationPipelineMetrics.ENABLED) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_READS);
                }

                boolean candidatePlaced = false;
                for (int targetIndex = 0; targetIndex < targets.length; targetIndex++) {
                    FastTarget target = targets[targetIndex];
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

                    if (shouldSkipAirCheck(random, airChance)
                            || !isAdjacentToAir(access, cachedSection, cachedRaw, airStates, tempPos, x, y, z, localX, localY, localZ, sectionIndex)) {
                        commitOrePlacement(
                                context,
                                cachedSection,
                                cachedRaw,
                                target,
                                currentStateId,
                                airStates,
                                targetPlan.placementMayBeAir(),
                                x,
                                y,
                                z,
                                localX,
                                localY,
                                localZ,
                                sectionIndex
                        );
                        placedAny = true;
                        candidatePlaced = true;
                        break;
                    }
                }

                if (!candidatePlaced && DecorationPipelineMetrics.ENABLED) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_KERNEL);
                }
            }
        }

        return placedAny;
    }

    private static boolean placeOreNative(
            OreConfiguration config,
            OreTargetPlan targetPlan,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int originX,
            int originY,
            int originZ
    ) {
        RandomSource random = context.random();
        float angle = random.nextFloat() * (float) Math.PI;
        float horizontalRadius = config.size / 8.0F;
        int heightRadius = Mth.ceil((config.size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double minX = originX + Math.sin(angle) * horizontalRadius;
        double maxX = originX - Math.sin(angle) * horizontalRadius;
        double minZ = originZ + Math.cos(angle) * horizontalRadius;
        double maxZ = originZ - Math.cos(angle) * horizontalRadius;
        double minY = originY + random.nextInt(3) - 2;
        double maxY = originY + random.nextInt(3) - 2;
        int startX = originX - Mth.ceil(horizontalRadius) - heightRadius;
        int startY = originY - 2 - heightRadius;
        int startZ = originZ - Mth.ceil(horizontalRadius) - heightRadius;
        int width = 2 * (Mth.ceil(horizontalRadius) + heightRadius);
        int height = 2 * (2 + heightRadius);

        if (!oreHasSurfaceBelow(context, scratch, startX, startY, startZ, width)) {
            return false;
        }

        return doPlaceOreNative(
                config,
                targetPlan,
                context,
                scratch,
                minX,
                maxX,
                minZ,
                maxZ,
                minY,
                maxY,
                startX,
                startY,
                startZ,
                width,
                height
        );
    }

    private static boolean oreHasSurfaceBelow(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int startX,
            int startY,
            int startZ,
            int width
    ) {
        WorldGenLevel level = context.level();
        ChunkAccess cachedChunk = null;
        Heightmap cachedHeightmap = null;
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;

        for (int x = startX; x <= startX + width; x++) {
            for (int z = startZ; z <= startZ + width; z++) {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                    cachedChunk = chunkX == context.chunkX() && chunkZ == context.chunkZ()
                            ? context.chunk()
                            : level.getChunk(chunkX, chunkZ);
                    cachedHeightmap = scratch.cachedHeightmap(cachedChunk, Heightmap.Types.OCEAN_FLOOR_WG);
                    lastChunkX = chunkX;
                    lastChunkZ = chunkZ;
                }

                boolean aboveFloor = cachedHeightmap != null
                        ? startY <= cachedHeightmap.getFirstAvailable(x & 15, z & 15) - 1
                        : startY <= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
                if (aboveFloor) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean doPlaceOreNative(
            OreConfiguration config,
            OreTargetPlan targetPlan,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            double minY,
            double maxY,
            int startX,
            int startY,
            int startZ,
            int width,
            int height
    ) {
        int size = config.size;
        int visitedBitCount = width * height * width;
        long[] visited = scratch.clearOreVisitedWords(visitedBitCount);
        double[] veinData = scratch.ensureOreVeinDataCapacity(size * 4);
        RandomSource random = context.random();

        for (int i = 0; i < size; i++) {
            int base = i * 4;
            float progress = (float) i / size;
            double centerX = Mth.lerp(progress, minX, maxX);
            double centerY = Mth.lerp(progress, minY, maxY);
            double centerZ = Mth.lerp(progress, minZ, maxZ);
            double randomScale = random.nextDouble() * size / 16.0;
            double radius = ((Mth.sin((float) Math.PI * progress) + 1.0F) * randomScale + 1.0) / 2.0;
            veinData[base] = centerX;
            veinData[base + 1] = centerY;
            veinData[base + 2] = centerZ;
            veinData[base + 3] = radius;
        }

        for (int left = 0; left < size - 1; left++) {
            int leftBase = left * 4;
            if (veinData[leftBase + 3] <= 0.0) {
                continue;
            }
            for (int right = left + 1; right < size; right++) {
                int rightBase = right * 4;
                if (veinData[rightBase + 3] <= 0.0) {
                    continue;
                }
                double dx = veinData[leftBase] - veinData[rightBase];
                double dy = veinData[leftBase + 1] - veinData[rightBase + 1];
                double dz = veinData[leftBase + 2] - veinData[rightBase + 2];
                double dr = veinData[leftBase + 3] - veinData[rightBase + 3];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0.0) {
                        veinData[rightBase + 3] = -1.0;
                    } else {
                        veinData[leftBase + 3] = -1.0;
                    }
                }
            }
        }

        FastTarget[] targets = targetPlan.targets();
        BlockState[] states = fastStateCache();
        boolean[] airStates = FastBlockStateCache.AIR_STATES;
        float airChance = config.discardChanceOnAirExposure;
        int levelMinY = context.level().getMinBuildHeight();
        int levelMaxY = context.level().getMaxBuildHeight() - 1;
        int planeStride = width * height;
        int placedCount = 0;
        boolean metricsEnabled = DecorationPipelineMetrics.ENABLED;
        long metricSectionSwitches = 0L;
        long metricBlockReads = 0L;
        long metricCandidatesGenerated = 0L;
        long metricKernelRejects = 0L;

        try (BulkSectionAccess access = new BulkSectionAccess(context.level())) {
            BlockPos.MutableBlockPos pos = scratch.mutablePos;
            BlockPos.MutableBlockPos tempPos = scratch.secondMutablePos;

            for (int vein = 0; vein < size; vein++) {
                int base = vein * 4;
                double radius = veinData[base + 3];
                if (radius < 0.0) {
                    continue;
                }

                double centerX = veinData[base];
                double centerY = veinData[base + 1];
                double centerZ = veinData[base + 2];
                double shiftedCenterX = centerX - 0.5D;
                double shiftedCenterY = centerY - 0.5D;
                double shiftedCenterZ = centerZ - 0.5D;

                int minBlockY = Math.max(Math.max(Mth.ceil(shiftedCenterY - radius), startY), levelMinY);
                int maxBlockY = Math.min(Math.min(Mth.floor(shiftedCenterY + radius), startY + height - 1), levelMaxY);
                if (minBlockY > maxBlockY) {
                    continue;
                }

                double invRadius = 1.0 / radius;
                LevelChunkSection cachedSection = null;
                int[] cachedRaw = null;
                int lastSecX = Integer.MIN_VALUE;
                int lastSecY = Integer.MIN_VALUE;
                int lastSecZ = Integer.MIN_VALUE;

                for (int blockY = minBlockY; blockY <= maxBlockY; blockY++) {
                    double dy = (blockY - shiftedCenterY) * invRadius;
                    double dySq = dy * dy;
                    if (dySq >= 1.0) {
                        continue;
                    }

                    double zRadius = Math.sqrt(1.0 - dySq) * radius;
                    int minBlockZ = Math.max(Mth.ceil(shiftedCenterZ - zRadius), startZ);
                    int maxBlockZ = Math.min(Mth.floor(shiftedCenterZ + zRadius), startZ + width - 1);
                    if (minBlockZ > maxBlockZ) {
                        continue;
                    }

                    int bitIndexY = (blockY - startY) * width;
                    int secY = blockY >> 4;
                    for (int blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ++) {
                        double dz = (blockZ - shiftedCenterZ) * invRadius;
                        double dyzSq = dySq + dz * dz;
                        if (dyzSq >= 1.0) {
                            continue;
                        }

                        double xRadius = Math.sqrt(1.0 - dyzSq) * radius;
                        int minBlockX = Math.max(Mth.ceil(shiftedCenterX - xRadius), startX);
                        int maxBlockX = Math.min(Mth.floor(shiftedCenterX + xRadius), startX + width - 1);
                        if (minBlockX > maxBlockX) {
                            continue;
                        }

                        int bitIndexYZ = bitIndexY + (blockZ - startZ) * planeStride;
                        int secZ = blockZ >> 4;
                        for (int blockX = minBlockX; blockX <= maxBlockX; blockX++) {
                            int bitIndex = (blockX - startX) + bitIndexYZ;
                            int wordIndex = bitIndex >>> 6;
                            long bitMask = 1L << (bitIndex & 63);
                            if ((visited[wordIndex] & bitMask) != 0L) {
                                continue;
                            }
                            visited[wordIndex] |= bitMask;

                            int secX = blockX >> 4;
                            if (secX != lastSecX || secY != lastSecY || secZ != lastSecZ) {
                                pos.set(blockX, blockY, blockZ);
                                cachedSection = access.getSection(pos);
                                cachedRaw = cachedSection == null ? null : LevelChunkSection$FlatBlockArray.rawData(cachedSection);
                                lastSecX = secX;
                                lastSecY = secY;
                                lastSecZ = secZ;
                                if (metricsEnabled) {
                                    metricSectionSwitches++;
                                }
                            }

                            if (cachedSection == null) {
                                if (metricsEnabled) {
                                    metricKernelRejects++;
                                }
                                continue;
                            }

                            int localX = blockX & 15;
                            int localY = blockY & 15;
                            int localZ = blockZ & 15;
                            int sectionIndex = (localY << 8) | (localZ << 4) | localX;

                            int currentStateId;
                            BlockState currentState = null;
                            if (cachedRaw != null) {
                                currentStateId = cachedRaw[sectionIndex];
                            } else {
                                currentState = cachedSection.getBlockState(localX, localY, localZ);
                                currentStateId = GA$BlockStateExtension.get(currentState).bts$getFastId();
                            }
                            if (metricsEnabled) {
                                metricBlockReads++;
                                metricCandidatesGenerated++;
                            }

                            for (int targetIndex = 0; targetIndex < targets.length; targetIndex++) {
                                FastTarget target = targets[targetIndex];
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

                                if (shouldSkipAirCheck(random, airChance)
                                        || !isAdjacentToAir(access, cachedSection, cachedRaw, airStates, tempPos, blockX, blockY, blockZ, localX, localY, localZ, sectionIndex)) {
                                    commitOrePlacement(
                                            context,
                                            cachedSection,
                                            cachedRaw,
                                            target,
                                            currentStateId,
                                            airStates,
                                            targetPlan.placementMayBeAir(),
                                            blockX,
                                            blockY,
                                            blockZ,
                                            localX,
                                            localY,
                                            localZ,
                                            sectionIndex
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
        if (metricsEnabled) {
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_SECTION_SWITCHES, metricSectionSwitches);
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_READS, metricBlockReads);
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.NATIVE_CANDIDATES_GENERATED, metricCandidatesGenerated);
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_KERNEL, metricKernelRejects);
        }

        return placedCount > 0;
    }

    private static int scatteredOreSpread(RandomSource random, int spread) {
        return Math.round((random.nextFloat() - random.nextFloat()) * (float) spread);
    }

    private static boolean shouldSkipAirCheck(RandomSource random, float chance) {
        return chance <= 0.0F || (chance < 1.0F && random.nextFloat() >= chance);
    }

    private static void commitOrePlacement(
            DecorationPipelineExecutor.ExecutionContext context,
            LevelChunkSection section,
            int[] raw,
            FastTarget target,
            int previousStateId,
            boolean[] airStates,
            boolean placementMayBeAir,
            int blockX,
            int blockY,
            int blockZ,
            int localX,
            int localY,
            int localZ,
            int sectionIndex
    ) {
        long start = DecorationPipelineMetrics.startTimer();
        if (raw != null && (!placementMayBeAir || airStates[previousStateId] == airStates[target.placementStateId()])) {
            raw[sectionIndex] = target.placementStateId();
        } else {
            section.setBlockState(localX, localY, localZ, target.placementState(), false);
        }
        if (DecorationWorkspaceBridge.hasCurrentWorkspace(context.workspace())) {
            DecorationWorkspaceBridge.mirrorCurrentWorkspaceWrite(
                    chunkFor(context, blockX, blockZ),
                    blockX,
                    blockY,
                    blockZ,
                    target.placementState()
            );
        }
        DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_COMMIT_NANOS, start);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
    }

    private static boolean isAdjacentToAir(
            BulkSectionAccess access,
            LevelChunkSection section,
            int[] raw,
            boolean[] airStates,
            BlockPos.MutableBlockPos tempPos,
            int globalX,
            int globalY,
            int globalZ,
            int localX,
            int localY,
            int localZ,
            int sectionIndex
    ) {
        if (raw != null) {
            if (localX > 0) {
                if (airStates[raw[sectionIndex - 1]]) return true;
            } else if (isAirAt(access, airStates, globalX - 1, globalY, globalZ, tempPos)) {
                return true;
            }

            if (localX < 15) {
                if (airStates[raw[sectionIndex + 1]]) return true;
            } else if (isAirAt(access, airStates, globalX + 1, globalY, globalZ, tempPos)) {
                return true;
            }

            if (localY > 0) {
                if (airStates[raw[sectionIndex - 256]]) return true;
            } else if (isAirAt(access, airStates, globalX, globalY - 1, globalZ, tempPos)) {
                return true;
            }

            if (localY < 15) {
                if (airStates[raw[sectionIndex + 256]]) return true;
            } else if (isAirAt(access, airStates, globalX, globalY + 1, globalZ, tempPos)) {
                return true;
            }

            if (localZ > 0) {
                if (airStates[raw[sectionIndex - 16]]) return true;
            } else if (isAirAt(access, airStates, globalX, globalY, globalZ - 1, tempPos)) {
                return true;
            }

            if (localZ < 15) {
                if (airStates[raw[sectionIndex + 16]]) return true;
            } else if (isAirAt(access, airStates, globalX, globalY, globalZ + 1, tempPos)) {
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

        return isAirAt(access, airStates, globalX + 1, globalY, globalZ, tempPos)
                || isAirAt(access, airStates, globalX - 1, globalY, globalZ, tempPos)
                || isAirAt(access, airStates, globalX, globalY + 1, globalZ, tempPos)
                || isAirAt(access, airStates, globalX, globalY - 1, globalZ, tempPos)
                || isAirAt(access, airStates, globalX, globalY, globalZ + 1, tempPos)
                || isAirAt(access, airStates, globalX, globalY, globalZ - 1, tempPos);
    }

    private static boolean isAirAt(
            BulkSectionAccess access,
            boolean[] airStates,
            int globalX,
            int globalY,
            int globalZ,
            BlockPos.MutableBlockPos tempPos
    ) {
        tempPos.set(globalX, globalY, globalZ);
        LevelChunkSection neighborSection = access.getSection(tempPos);
        if (neighborSection == null) {
            return true;
        }

        int[] neighborRaw = LevelChunkSection$FlatBlockArray.rawData(neighborSection);
        if (neighborRaw != null) {
            int index = ((globalY & 15) << 8) | ((globalZ & 15) << 4) | (globalX & 15);
            return airStates[neighborRaw[index]];
        }

        return access.getBlockState(tempPos).isAir();
    }

    private static BlockState[] fastStateCache() {
        BlockState[] states = FastBlockStateCache.STATES;
        if (states == null) {
            FastBlockStateCache.init(GeneratorAccelerator.platform);
            states = FastBlockStateCache.STATES;
        }
        return states;
    }

    private static Boolean tryPlaceWaterPlantNative(
            Feature<?> feature,
            FeatureConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z
    ) {
        if (feature == Feature.KELP) {
            return placeKelpNative(context, scratch, x, z);
        }
        if (feature == Feature.SEAGRASS && config instanceof ProbabilityFeatureConfiguration probabilityConfig) {
            return placeSeagrassNative(probabilityConfig, context, scratch, x, z);
        }
        if (feature == Feature.SEA_PICKLE && config instanceof CountConfiguration countConfig) {
            return placeSeaPickleNative(countConfig, context, scratch, x, z);
        }
        return null;
    }

    private static boolean placeSeaPickleNative(
            CountConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int originX,
            int originZ
    ) {
        int count = config.count().sample(context.random());
        int placed = 0;
        BlockPos.MutableBlockPos pos = scratch.mutablePos;
        for (int i = 0; i < count; i++) {
            int x = originX + context.random().nextInt(8) - context.random().nextInt(8);
            int z = originZ + context.random().nextInt(8) - context.random().nextInt(8);
            int y = fastHeight(context, scratch, Heightmap.Types.OCEAN_FLOOR, x, z);
            pos.set(x, y, z);

            BlockState state = SEA_PICKLES[context.random().nextInt(4)];
            if (!descriptorHasWaterAt(scratch, x, y, z, true)) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
                noteDescriptorColumnReject(1L);
                continue;
            }
            boolean exactWater = descriptorHasExactWaterAt(scratch, x, y, z);
            if (!exactWater && !context.level().getBlockState(pos).is(Blocks.WATER)) {
                continue;
            }
            if (exactWater) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED);
            }
            if (state.canSurvive(context.level(), pos)) {
                setBlockTracked(context, scratch, pos, state, 2);
                placed++;
            }
        }
        DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_WRITES, placed);
        return placed > 0;
    }

    private static boolean placeSpringNative(
            SpringConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z
    ) {
        WorldGenLevel level = context.level();
        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(x, y, z);
        if (!level.ensureCanWrite(pos)) {
            return false;
        }
        if (!passesSpringDescriptorGate(scratch, x, y, z)) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
            return false;
        }

        boolean shouldPlace;
        try (BulkSectionAccess access = new BulkSectionAccess(level)) {
            BlockPos.MutableBlockPos temp = scratch.secondMutablePos;
            BlockState aboveState = springBlockState(level, access, temp, x, y + 1, z);
            if (!aboveState.is(config.validBlocks)) {
                return false;
            }

            BlockState belowState = springBlockState(level, access, temp, x, y - 1, z);
            if (config.requiresBlockBelow && !belowState.is(config.validBlocks)) {
                return false;
            }

            BlockState centerState = springBlockState(level, access, temp, x, y, z);
            if (!centerState.isAir() && !centerState.is(config.validBlocks)) {
                return false;
            }

            BlockState westState = springBlockState(level, access, temp, x - 1, y, z);
            BlockState eastState = springBlockState(level, access, temp, x + 1, y, z);
            BlockState northState = springBlockState(level, access, temp, x, y, z - 1);
            BlockState southState = springBlockState(level, access, temp, x, y, z + 1);

            int rockCount = 0;
            if (westState.is(config.validBlocks)) rockCount++;
            if (eastState.is(config.validBlocks)) rockCount++;
            if (northState.is(config.validBlocks)) rockCount++;
            if (southState.is(config.validBlocks)) rockCount++;
            if (belowState.is(config.validBlocks)) rockCount++;

            int holeCount = 0;
            if (westState.isAir()) holeCount++;
            if (eastState.isAir()) holeCount++;
            if (northState.isAir()) holeCount++;
            if (southState.isAir()) holeCount++;
            if (belowState.isAir()) holeCount++;

            shouldPlace = rockCount == config.rockCount && holeCount == config.holeCount;
        }

        if (!shouldPlace) {
            return false;
        }

        setBlockTracked(context, scratch, pos, config.state.createLegacyBlock(), 2);
        level.scheduleTick(pos, config.state.getType(), 0);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
        return true;
    }

    private static boolean placeDiskNative(
            DiskConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int centerX,
            int centerY,
            int centerZ
    ) {
        int topY = centerY + config.halfHeight();
        int bottomY = centerY - config.halfHeight() - 1;
        int radius = config.radius().sample(context.random());
        int radiusSq = radius * radius;
        int minChunkX = (centerX - radius) >> 4;
        int maxChunkX = (centerX + radius) >> 4;
        int minChunkZ = (centerZ - radius) >> 4;
        int maxChunkZ = (centerZ + radius) >> 4;
        boolean singleChunkDisk = minChunkX == maxChunkX && minChunkZ == maxChunkZ;
        BlockPos.MutableBlockPos pos = scratch.mutablePos;
        BlockPos.MutableBlockPos markPos = scratch.secondMutablePos;
        boolean placedAny = false;
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            int dx = x - centerX;
            int dxSq = dx * dx;
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                int dz = z - centerZ;
                if (dxSq + dz * dz > radiusSq) {
                    continue;
                }
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                if (singleChunkDisk && (chunkX != lastChunkX || chunkZ != lastChunkZ)) {
                    scratch.chunkWriter.begin(chunkFor(context, x, z));
                    lastChunkX = chunkX;
                    lastChunkZ = chunkZ;
                }

                for (int y = topY; y > bottomY; y--) {
                    pos.set(x, y, z);
                    if (!context.level().ensureCanWrite(pos)) {
                        continue;
                    }
                    if (!config.target().test(context.level(), pos)) {
                        continue;
                    }

                    BlockState state = config.stateProvider().getState(context.level(), context.random(), pos);
                    if (singleChunkDisk) {
                        setChunkWriterBlockTracked(context, scratch, pos, state);
                        markAboveForPostProcessing(scratch, markPos.set(pos));
                        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
                        placedAny = true;
                    } else {
                        if (setBlockTracked(context, scratch, pos, state, 2)) {
                            markAboveForPostProcessing(context.level(), markPos.set(pos));
                            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
                            placedAny = true;
                        }
                    }
                }
            }
        }

        return placedAny;
    }

    private static boolean placeBlockColumnNative(
            BlockColumnConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z
    ) {
        CompiledBlockColumn compiled = BLOCK_COLUMN_CACHE.getOrCompute(config, DecorationPlacementProgram::compileBlockColumn);
        BlockColumnConfiguration.Layer[] layers = compiled.layers();
        int layerCount = layers.length;
        int[] heights = blockColumnHeights(layerCount);
        int totalHeight = 0;

        for (int i = 0; i < layerCount; i++) {
            int sampledHeight = layers[i].height().sample(context.random());
            heights[i] = sampledHeight;
            totalHeight += sampledHeight;
        }
        if (totalHeight == 0) {
            return false;
        }

        Direction direction = compiled.direction();
        BlockPos.MutableBlockPos placePos = scratch.mutablePos.set(x, y, z);
        if (!context.level().ensureCanWrite(placePos)) {
            return false;
        }
        BlockPos.MutableBlockPos probePos = scratch.secondMutablePos.set(x, y, z).move(direction);
        for (int step = 0; step < totalHeight; step++) {
            if (!compiled.allowedPlacement().test(context.level(), probePos)) {
                truncateBlockColumn(heights, totalHeight, step, compiled.prioritizeTip());
                break;
            }
            probePos.move(direction);
        }

        boolean placedAny = false;
        for (int layerIndex = 0; layerIndex < layerCount; layerIndex++) {
            int height = heights[layerIndex];
            if (height == 0) {
                continue;
            }

            BlockColumnConfiguration.Layer layer = layers[layerIndex];
            for (int step = 0; step < height; step++) {
                if (!context.level().ensureCanWrite(placePos)) {
                    return placedAny;
                }
                setBlockTracked(context, scratch, placePos, layer.state().getState(context.random(), placePos), 2);
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
                placedAny = true;
                placePos.move(direction);
            }
        }

        return placedAny;
    }

    private static void markAboveForPostProcessing(DecorationPipelineScratch scratch, BlockPos.MutableBlockPos pos) {
        for (int i = 0; i < 2; i++) {
            pos.move(Direction.UP);
            if (scratch.chunkWriter.getBlockState(pos).isAir()) {
                return;
            }
            scratch.chunkWriter.markPosForPostprocessing(pos);
        }
    }

    private static void markAboveForPostProcessing(WorldGenLevel level, BlockPos.MutableBlockPos pos) {
        for (int i = 0; i < 2; i++) {
            pos.move(Direction.UP);
            if (level.getBlockState(pos).isAir()) {
                return;
            }
            level.getChunk(pos).markPosForPostprocessing(pos);
        }
    }

    private static CompiledBlockColumn compileBlockColumn(BlockColumnConfiguration config) {
        List<BlockColumnConfiguration.Layer> list = config.layers();
        return new CompiledBlockColumn(
                list.toArray(new BlockColumnConfiguration.Layer[0]),
                config.direction(),
                config.allowedPlacement(),
                config.prioritizeTip()
        );
    }

    private static int[] blockColumnHeights(int requiredLength) {
        int[] heights = BLOCK_COLUMN_HEIGHTS.get();
        if (heights.length < requiredLength) {
            heights = new int[requiredLength];
            BLOCK_COLUMN_HEIGHTS.set(heights);
        }
        return heights;
    }

    private static void truncateBlockColumn(int[] layers, int totalHeight, int placedHeight, boolean prioritizeTip) {
        int remaining = totalHeight - placedHeight;
        int step = prioritizeTip ? 1 : -1;
        int start = prioritizeTip ? 0 : layers.length - 1;
        int end = prioritizeTip ? layers.length : -1;

        for (int index = start; index != end && remaining > 0; index += step) {
            int removed = Math.min(layers[index], remaining);
            remaining -= removed;
            layers[index] -= removed;
        }
    }

    private static BlockState springBlockState(
            WorldGenLevel level,
            BulkSectionAccess access,
            BlockPos.MutableBlockPos pos,
            int x,
            int y,
            int z
    ) {
        pos.set(x, y, z);
        if (y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()) {
            LevelChunkSection section = access.getSection(pos);
            if (section != null) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_READS);
                return section.getBlockState(x & 15, y & 15, z & 15);
            }
        }

        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_READS);
        return level.getBlockState(pos);
    }

    private static boolean placeSeagrassNative(
            ProbabilityFeatureConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int originX,
            int originZ
    ) {
        int x = originX + context.random().nextInt(8) - context.random().nextInt(8);
        int z = originZ + context.random().nextInt(8) - context.random().nextInt(8);
        int y = fastHeight(context, scratch, Heightmap.Types.OCEAN_FLOOR, x, z);
        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(x, y, z);
        if (!context.level().ensureCanWrite(pos)) {
            return false;
        }
        if (!descriptorHasWaterAt(scratch, x, y, z, true)) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
            noteDescriptorColumnReject(1L);
            return false;
        }
        boolean exactWater = descriptorHasExactWaterAt(scratch, x, y, z);
        if (!exactWater && !context.level().getBlockState(pos).is(Blocks.WATER)) {
            return false;
        }
        if (exactWater) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED);
        }

        boolean tall = context.random().nextDouble() < config.probability;
        BlockState state = tall ? TALL_SEAGRASS_LOWER : SEAGRASS_STATE;
        if (!state.canSurvive(context.level(), pos)) {
            return false;
        }

        if (tall) {
            BlockPos.MutableBlockPos above = scratch.secondMutablePos.set(x, y + 1, z);
            if (!descriptorHasWaterAt(scratch, x, y + 1, z, false)) {
                return false;
            }
            boolean exactAboveWater = descriptorHasExactWaterAt(scratch, x, y + 1, z);
            if (!exactAboveWater && !context.level().getBlockState(above).is(Blocks.WATER)) {
                return false;
            }
            if (exactAboveWater) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED);
            }
            setBlockTracked(context, scratch, pos, state, 2);
            setBlockTracked(context, scratch, above, TALL_SEAGRASS_UPPER, 2);
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_WRITES, 2L);
            return true;
        }

        setBlockTracked(context, scratch, pos, state, 2);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
        return true;
    }

    private static boolean placeKelpNative(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int z
    ) {
        int y = fastHeight(context, scratch, Heightmap.Types.OCEAN_FLOOR, x, z);
        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(x, y, z);
        if (!context.level().ensureCanWrite(pos)) {
            return false;
        }
        if (!descriptorHasWaterAt(scratch, x, y, z, true)) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
            noteDescriptorColumnReject(1L);
            return false;
        }
        boolean exactWater = descriptorHasExactWaterAt(scratch, x, y, z);
        if (!exactWater && !context.level().getBlockState(pos).is(Blocks.WATER)) {
            return false;
        }
        if (exactWater) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED);
        }

        BlockPos.MutableBlockPos temp = scratch.secondMutablePos;
        int maxHeight = 1 + context.random().nextInt(10);
        int placedHeads = 0;
        for (int step = 0; step <= maxHeight; step++) {
            int posY = pos.getY();
            temp.set(x, posY + 1, z);
            boolean exactColumnWater = descriptorHasExactWaterAt(scratch, x, posY, z);
            boolean exactAboveWater = descriptorHasExactWaterAt(scratch, x, posY + 1, z);
            if (descriptorHasWaterAt(scratch, x, posY, z, false)
                    && (exactColumnWater || context.level().getBlockState(pos).is(Blocks.WATER))
                    && descriptorHasWaterAt(scratch, x, posY + 1, z, false)
                    && (exactAboveWater || context.level().getBlockState(temp).is(Blocks.WATER))
                    && KELP_PLANT.canSurvive(context.level(), pos)) {
                if (exactColumnWater) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED);
                }
                if (exactAboveWater) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED);
                }
                if (step == maxHeight) {
                    setBlockTracked(context, scratch, pos, KELP_HEADS[context.random().nextInt(4)], 2);
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
                    placedHeads++;
                    break;
                }

                setBlockTracked(context, scratch, pos, KELP_PLANT, 2);
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
            } else if (step > 0) {
                int belowY = posY - 1;
                temp.set(x, belowY, z);
                if (KELP_HEADS[0].canSurvive(context.level(), temp)) {
                    temp.set(x, belowY - 1, z);
                    if (!context.level().getBlockState(temp).is(Blocks.KELP)) {
                        temp.set(x, belowY, z);
                        setBlockTracked(context, scratch, temp, KELP_HEADS[context.random().nextInt(4)], 2);
                        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
                        placedHeads++;
                    }
                }
                break;
            } else {
                break;
            }

            pos.setY(posY + 1);
        }

        return placedHeads > 0;
    }

    private static boolean placeSimpleBlockNative(
            SimpleBlockConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z
    ) {
        if (!passesPlantDescriptorGate(scratch, x, y, z)) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
            return false;
        }
        BlockPos.MutableBlockPos origin = scratch.mutablePos.set(x, y, z);
        BlockState state = config.toPlace().getState(context.random(), origin);
        if (!passesSimpleBlockMicroGate(state, scratch, x, y, z)) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
            return false;
        }
        if (scratch.isCollectingSimpleBlockBatch()) {
            scratch.addSimpleBlockCandidate(state, x, y, z);
            return true;
        }
        scratch.chunkWriter.begin(chunkFor(context, x, z));
        return placeSimpleBlockPrepared(state, context, scratch, x, y, z);
    }

    private static boolean placeSnowAndFreezeNative(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int originX,
            int originZ
    ) {
        WorldGenLevel level = context.level();
        BlockPos.MutableBlockPos top = scratch.mutablePos;
        BlockPos.MutableBlockPos below = scratch.secondMutablePos;
        long writes = 0L;

        scratch.chunkWriter.begin(context.chunk());
        for (int dx = 0; dx < 16; dx++) {
            int x = originX + dx;
            for (int dz = 0; dz < 16; dz++) {
                int z = originZ + dz;
                int y = fastHeight(context, scratch, Heightmap.Types.MOTION_BLOCKING, x, z);
                top.set(x, y, z);
                below.set(x, y - 1, z);
                Biome biome = level.getBiome(top).value();

                if (biome.shouldFreeze(level, below, false)) {
                    setChunkWriterBlockTracked(context, scratch, below, ICE_STATE);
                    writes++;
                }
                if (biome.shouldSnow(level, top)) {
                    setChunkWriterBlockTracked(context, scratch, top, SNOW_STATE);
                    writes++;
                    BlockState belowState = scratch.chunkWriter.getBlockState(below);
                    if (belowState.hasProperty(SnowyDirtBlock.SNOWY)) {
                        setChunkWriterBlockTracked(context, scratch, below, belowState.setValue(SnowyDirtBlock.SNOWY, true));
                        writes++;
                    }
                }
            }
        }

        DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_WRITES, writes);
        return true;
    }

    private static boolean placeLakeNative(
            LakeFeature.Configuration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int originX,
            int originY,
            int originZ
    ) {
        WorldGenLevel level = context.level();
        if (originY <= level.getMinBuildHeight() + 4) {
            return false;
        }

        int originBaseY = originY - 4;
        BlockPos.MutableBlockPos origin = scratch.secondMutablePos.set(originX, originBaseY, originZ);
        boolean[] mask = scratch.clearLakeMask();
        RandomSource random = context.random();

        int ellipsoidCount = random.nextInt(4) + 4;
        for (int i = 0; i < ellipsoidCount; i++) {
            double sizeX = random.nextDouble() * 6.0D + 3.0D;
            double sizeY = random.nextDouble() * 4.0D + 2.0D;
            double sizeZ = random.nextDouble() * 6.0D + 3.0D;
            double centerX = random.nextDouble() * (16.0D - sizeX - 2.0D) + 1.0D + sizeX / 2.0D;
            double centerY = random.nextDouble() * (8.0D - sizeY - 4.0D) + 2.0D + sizeY / 2.0D;
            double centerZ = random.nextDouble() * (16.0D - sizeZ - 2.0D) + 1.0D + sizeZ / 2.0D;

            for (int x = 1; x < 15; x++) {
                double normX = (x - centerX) / (sizeX / 2.0D);
                for (int z = 1; z < 15; z++) {
                    double normZ = (z - centerZ) / (sizeZ / 2.0D);
                    for (int y = 1; y < 7; y++) {
                        double normY = (y - centerY) / (sizeY / 2.0D);
                        if (normX * normX + normY * normY + normZ * normZ < 1.0D) {
                            mask[lakeIndex(x, z, y)] = true;
                        }
                    }
                }
            }
        }

        BlockState fluidState = config.fluid().getState(random, origin);
        BlockPos.MutableBlockPos pos = scratch.mutablePos;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 8; y++) {
                    if (mask[lakeIndex(x, z, y)] || !hasLakeNeighbour(mask, x, z, y)) {
                        continue;
                    }

                    pos.set(originX + x, originBaseY + y, originZ + z);
                    BlockState state = level.getBlockState(pos);
                    if (y >= 4) {
                        if (state.liquid()) {
                            return false;
                        }
                    } else if (!state.isSolid() && state != fluidState) {
                        return false;
                    }
                }
            }
        }

        long writes = 0L;
        boolean placedAny = false;
        BlockPos.MutableBlockPos markPos = scratch.secondMutablePos;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 8; y++) {
                    if (!mask[lakeIndex(x, z, y)]) {
                        continue;
                    }

                    pos.set(originX + x, originBaseY + y, originZ + z);
                    if (!lakeCanReplaceBlock(level.getBlockState(pos))) {
                        continue;
                    }

                    boolean upperHalf = y >= 4;
                    if (setBlockTracked(context, scratch, pos, upperHalf ? CAVE_AIR_STATE : fluidState, 2)) {
                        writes++;
                        placedAny = true;
                    }
                    if (upperHalf) {
                        level.scheduleTick(pos, CAVE_AIR_STATE.getBlock(), 0);
                        markAboveForPostProcessing(level, markPos.set(pos));
                    }
                }
            }
        }

        origin.set(originX, originBaseY, originZ);
        BlockState barrierState = config.barrier().getState(random, origin);
        if (!barrierState.isAir()) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 8; y++) {
                        if (mask[lakeIndex(x, z, y)] || !hasLakeNeighbour(mask, x, z, y)) {
                            continue;
                        }
                        if (y >= 4 && random.nextInt(2) == 0) {
                            continue;
                        }

                        pos.set(originX + x, originBaseY + y, originZ + z);
                        BlockState state = level.getBlockState(pos);
                        if (!state.isSolid() || state.is(BlockTags.LAVA_POOL_STONE_CANNOT_REPLACE)) {
                            continue;
                        }

                        if (setBlockTracked(context, scratch, pos, barrierState, 2)) {
                            writes++;
                            placedAny = true;
                        }
                        markAboveForPostProcessing(level, markPos.set(pos));
                    }
                }
            }
        }

        if (fluidState.getFluidState().is(FluidTags.WATER)) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    pos.set(originX + x, originBaseY + 4, originZ + z);
                    Biome biome = level.getBiome(pos).value();
                    if (biome.shouldFreeze(level, pos, false)
                            && lakeCanReplaceBlock(level.getBlockState(pos))
                            && setBlockTracked(context, scratch, pos, ICE_STATE, 2)) {
                        writes++;
                        placedAny = true;
                    }
                }
            }
        }

        DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_WRITES, writes);
        return placedAny;
    }

    private static boolean placeSculkPatchNative(
            SculkPatchConfiguration config,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z
    ) {
        WorldGenLevel level = context.level();
        BlockPos.MutableBlockPos origin = scratch.mutablePos.set(x, y, z);
        BlockPos.MutableBlockPos candidatePos = scratch.mutablePos;
        BlockPos.MutableBlockPos probe = scratch.secondMutablePos;
        if (!canSpreadSculkFrom(level, origin, probe)) {
            return false;
        }

        RandomSource random = context.random();
        SculkSpreader spreader = scratch.worldGenSculkSpreader();
        int chargeCount = config.chargeCount();
        int amountPerCharge = config.amountPerCharge();
        int spreadAttempts = config.spreadAttempts();
        int spreadRounds = config.spreadRounds();
        int totalRounds = spreadRounds + config.growthRounds();

        for (int round = 0; round < totalRounds; round++) {
            for (int charge = 0; charge < chargeCount; charge++) {
                spreader.addCursors(origin, amountPerCharge);
            }

            boolean shouldSpread = round < spreadRounds;
            for (int attempt = 0; attempt < spreadAttempts; attempt++) {
                spreader.updateCursors(level, origin, random, shouldSpread);
            }
            spreader.clear();
        }

        probe.set(x, y - 1, z);
        if (random.nextFloat() <= config.catalystChance()) {
            BlockState belowState = level.getBlockState(probe);
            if (belowState.isCollisionShapeFullBlock(level, probe)
                    && setBlockTracked(context, scratch, origin, SCULK_CATALYST_STATE, 3)) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
            }
        }

        int extraRareGrowths = config.extraRareGrowths().sample(random);
        for (int i = 0; i < extraRareGrowths; i++) {
            int candidateX = x + random.nextInt(5) - 2;
            int candidateZ = z + random.nextInt(5) - 2;
            candidatePos.set(candidateX, y, candidateZ);
            if (!level.getBlockState(candidatePos).isAir()) {
                continue;
            }

            probe.set(candidateX, y - 1, candidateZ);
            BlockState support = level.getBlockState(probe);
            if (!support.isFaceSturdy(level, probe, Direction.UP)) {
                continue;
            }

            if (setBlockTracked(context, scratch, candidatePos, SCULK_SHRIEKER_SUMMON_STATE, 3)) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
            }
        }
        return true;
    }

    private static boolean flushWriteJournal(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch
    ) {
        int count = scratch.candidateCount;
        if (count <= 0) {
            scratch.finishWriteJournal();
            return false;
        }

        long commitStart = DecorationPipelineMetrics.startTimer();
        boolean placedAny = false;
        try {
            for (int chunkBucket = 0; chunkBucket < scratch.chunkBucketCount; chunkBucket++) {
                int firstSectionBucket = scratch.chunkBucketHead[chunkBucket];
                if (firstSectionBucket < 0) {
                    continue;
                }
                int firstCandidate = scratch.sectionBucketHead[firstSectionBucket];
                if (firstCandidate < 0) {
                    continue;
                }
                ChunkAccess currentChunk = chunkFor(
                        context,
                        scratch.candidateX[firstCandidate],
                        scratch.candidateZ[firstCandidate]
                );
                scratch.chunkWriter.begin(currentChunk);
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.JOURNAL_COMMIT_BATCHES);
                for (int bucket = firstSectionBucket; bucket >= 0; bucket = scratch.sectionBucketNextInChunk[bucket]) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_SECTION_BATCHES);
                    for (int candidate = scratch.sectionBucketHead[bucket]; candidate >= 0; candidate = scratch.candidateNext[candidate]) {
                        if (commitJournalWrite(context, scratch, currentChunk, candidate)) {
                            placedAny = true;
                        }
                    }
                }
            }
            scratch.flushJournalDescriptorMutations();
        } finally {
            DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_COMMIT_NANOS, commitStart);
            scratch.finishWriteJournal();
        }
        return placedAny;
    }

    private static boolean commitJournalWrite(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            ChunkAccess currentChunk,
            int candidate
    ) {
        int flags = scratch.candidateWriteFlags[candidate];
        if ((flags & DecorationPipelineScratch.WRITE_FLAG_SIMPLE_BLOCK_SURVIVAL) != 0) {
            return placeSimpleBlockPrepared(
                    scratch.candidateSimpleBlockState[candidate],
                    context,
                    scratch,
                    scratch.candidateX[candidate],
                    scratch.candidateY[candidate],
                    scratch.candidateZ[candidate],
                    true,
                    currentChunk
            );
        }

        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(
                scratch.candidateX[candidate],
                scratch.candidateY[candidate],
                scratch.candidateZ[candidate]
        );
        setChunkWriterBlockJournaled(context, scratch, currentChunk, pos, scratch.candidateSimpleBlockState[candidate]);
        if ((flags & DecorationPipelineScratch.WRITE_FLAG_MARK_ABOVE_FOR_POSTPROCESSING) != 0) {
            markAboveForPostProcessing(scratch, scratch.secondMutablePos.set(pos));
        }
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.JOURNAL_WRITES_COMMITTED);
        return true;
    }

    private static boolean placeSimpleBlockPrepared(
            BlockState state,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z
    ) {
        return placeSimpleBlockPrepared(state, context, scratch, x, y, z, false);
    }

    private static boolean placeSimpleBlockPrepared(
            BlockState state,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z,
            boolean journaled
    ) {
        return placeSimpleBlockPrepared(state, context, scratch, x, y, z, journaled, null);
    }

    private static boolean placeSimpleBlockPrepared(
            BlockState state,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z,
            boolean journaled,
            ChunkAccess journalChunk
    ) {
        BlockPos.MutableBlockPos origin = scratch.mutablePos.set(x, y, z);
        if (!context.level().ensureCanWrite(origin)) {
            return false;
        }
        if (!state.canSurvive(context.level(), origin)) {
            return false;
        }

        if (state.getBlock() instanceof DoublePlantBlock) {
            SectionDescriptor aboveDescriptor = scratch.descriptors.findByBlockPos(x, y + 1, z);
            if (aboveDescriptor != null && !aboveDescriptor.columnHasOpenAt(x, y + 1, z)) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR);
                noteDescriptorColumnReject(1L);
                return false;
            }
            BlockPos.MutableBlockPos above = scratch.secondMutablePos.set(x, y + 1, z);
            if (!context.level().ensureCanWrite(above) || !scratch.chunkWriter.getBlockState(above).isAir()) {
                return false;
            }
            if (journaled) {
                ChunkAccess chunk = journalChunk != null ? journalChunk : chunkFor(context, x, z);
                setChunkWriterBlockJournaled(context, scratch, chunk, origin, state);
                setChunkWriterBlockJournaled(context, scratch, chunk, above, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
                DecorationPipelineMetrics.add(DecorationPipelineMetrics.JOURNAL_WRITES_COMMITTED, 2L);
            } else {
                setChunkWriterBlockTracked(context, scratch, origin, state);
                setChunkWriterBlockTracked(context, scratch, above, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
            }
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_WRITES, 2L);
            return true;
        }

        if (journaled) {
            setChunkWriterBlockJournaled(context, scratch, journalChunk != null ? journalChunk : chunkFor(context, x, z), origin, state);
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.JOURNAL_WRITES_COMMITTED);
        } else {
            setChunkWriterBlockTracked(context, scratch, origin, state);
        }
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
        return true;
    }

    private static ChunkAccess chunkFor(
            DecorationPipelineExecutor.ExecutionContext context,
            int x,
            int z
    ) {
        ChunkAccess centerChunk = context.chunk();
        if ((x >> 4) == context.chunkX() && (z >> 4) == context.chunkZ()) {
            return centerChunk;
        }
        return context.level().getChunk(x >> 4, z >> 4);
    }

    private static boolean passesSimpleBlockMicroGate(BlockState state, DecorationPipelineScratch scratch, int x, int y, int z) {
        if (state.getBlock() instanceof DoublePlantBlock) {
            SectionDescriptor aboveDescriptor = scratch.descriptors.findByBlockPos(x, y + 1, z);
            if (aboveDescriptor != null && !aboveDescriptor.columnHasOpenAt(x, y + 1, z)) {
                noteDescriptorColumnReject(1L);
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_SIMPLE_BLOCK_MICRO_REJECTS);
                return false;
            }
        }
        if (!isGroundSimpleBlockCandidate(state)) {
            return true;
        }
        SectionDescriptor supportDescriptor = scratch.descriptors.findByBlockPos(x, y - 1, z);
        if (supportDescriptor == null) {
            return true;
        }
        if (!supportDescriptor.columnHasGroundSupportAt(x, y - 1, z)) {
            noteDescriptorColumnReject(2L);
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_SIMPLE_BLOCK_MICRO_REJECTS);
            return false;
        }
        return true;
    }

    private static boolean isGroundSimpleBlockCandidate(BlockState state) {
        if (state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.CROPS)) {
            return true;
        }
        Block block = state.getBlock();
        return block == Blocks.SHORT_GRASS
                || block == Blocks.TALL_GRASS
                || block == Blocks.FERN
                || block == Blocks.LARGE_FERN
                || block == Blocks.BROWN_MUSHROOM
                || block == Blocks.RED_MUSHROOM
                || block == Blocks.CRIMSON_FUNGUS
                || block == Blocks.WARPED_FUNGUS
                || block == Blocks.CRIMSON_ROOTS
                || block == Blocks.WARPED_ROOTS
                || block == Blocks.NETHER_SPROUTS;
    }

    private static boolean passesPlantDescriptorGate(DecorationPipelineScratch scratch, int x, int y, int z) {
        SectionDescriptor descriptor = scratch.descriptors.findByBlockPos(x, y, z);
        if (descriptor == null) {
            return true;
        }
        int chunkPaletteFlags = scratch.descriptors.chunkColumnPaletteFlags(x >> 4, z >> 4, x & 15, z & 15);
        int chunkBlockClassFlags = scratch.descriptors.chunkColumnBlockClassFlags(x >> 4, z >> 4, x & 15, z & 15);
        if ((chunkBlockClassFlags & (SectionDescriptor.CLASS_SURFACE_CANDIDATE | SectionDescriptor.CLASS_DIRT_LIKE | SectionDescriptor.CLASS_REPLACEABLE)) == 0
                && (chunkPaletteFlags & SectionDescriptor.PALETTE_AIR) == 0) {
            noteDescriptorColumnReject(1L);
            return false;
        }
        if (!descriptor.hasSurfaceCandidate && !descriptor.hasDirtLike && !descriptor.hasAir && !descriptor.hasReplaceable) {
            noteDescriptorSectionReject(1L);
            return false;
        }
        int localX = x & 15;
        int localZ = z & 15;
        if (!descriptor.columnHasBlockClassFlag(localX, localZ, SectionDescriptor.CLASS_SURFACE_CANDIDATE | SectionDescriptor.CLASS_DIRT_LIKE | SectionDescriptor.CLASS_REPLACEABLE)
                && !descriptor.columnHasPaletteFlag(localX, localZ, SectionDescriptor.PALETTE_AIR)) {
            noteDescriptorColumnReject(1L);
            return false;
        }
        return true;
    }

    private static boolean passesSpringDescriptorGate(DecorationPipelineScratch scratch, int x, int y, int z) {
        SectionDescriptor descriptor = scratch.descriptors.findByBlockPos(x, y, z);
        if (descriptor == null) {
            return true;
        }
        int chunkPaletteFlags = scratch.descriptors.chunkColumnPaletteFlags(x >> 4, z >> 4, x & 15, z & 15);
        int chunkBlockClassFlags = scratch.descriptors.chunkColumnBlockClassFlags(x >> 4, z >> 4, x & 15, z & 15);
        if ((chunkPaletteFlags & (SectionDescriptor.PALETTE_AIR | SectionDescriptor.PALETTE_SOLID | SectionDescriptor.PALETTE_WATER | SectionDescriptor.PALETTE_LAVA)) == 0
                && (chunkBlockClassFlags & (SectionDescriptor.CLASS_STONE_LIKE | SectionDescriptor.CLASS_DIRT_LIKE | SectionDescriptor.CLASS_REPLACEABLE)) == 0) {
            noteDescriptorColumnReject(7L);
            return false;
        }
        if (!descriptor.hasAir && !descriptor.hasStoneLike && !descriptor.hasDirtLike) {
            noteDescriptorSectionReject(7L);
            return false;
        }
        int localX = x & 15;
        int localZ = z & 15;
        if (!descriptor.columnHasPaletteFlag(localX, localZ, SectionDescriptor.PALETTE_AIR | SectionDescriptor.PALETTE_SOLID | SectionDescriptor.PALETTE_WATER | SectionDescriptor.PALETTE_LAVA)
                && !descriptor.columnHasBlockClassFlag(localX, localZ, SectionDescriptor.CLASS_STONE_LIKE | SectionDescriptor.CLASS_DIRT_LIKE | SectionDescriptor.CLASS_REPLACEABLE)) {
            noteDescriptorColumnReject(7L);
            return false;
        }
        if (descriptor.columnHasFluidAt(x, y, z)) {
            noteDescriptorColumnReject(7L);
            return false;
        }
        SectionDescriptor aboveDescriptor = scratch.descriptors.findByBlockPos(x, y + 1, z);
        if (aboveDescriptor != null && !aboveDescriptor.columnHasSolidAt(x, y + 1, z)) {
            noteDescriptorColumnReject(7L);
            return false;
        }
        SectionDescriptor belowDescriptor = scratch.descriptors.findByBlockPos(x, y - 1, z);
        if (belowDescriptor != null && !belowDescriptor.columnHasAirAt(x, y - 1, z) && !belowDescriptor.columnHasSolidAt(x, y - 1, z)) {
            noteDescriptorColumnReject(7L);
            return false;
        }
        return true;
    }

    private static boolean passesTreeDescriptorGate(DecorationPipelineScratch scratch, int x, int y, int z) {
        SectionDescriptor originDescriptor = scratch.descriptors.findByBlockPos(x, y, z);
        if (originDescriptor == null) {
            return true;
        }
        int chunkBlockClassFlags = scratch.descriptors.chunkColumnBlockClassFlags(x >> 4, z >> 4, x & 15, z & 15);
        int chunkPaletteFlags = scratch.descriptors.chunkColumnPaletteFlags(x >> 4, z >> 4, x & 15, z & 15);
        if ((chunkPaletteFlags & SectionDescriptor.PALETTE_AIR) == 0
                && (chunkBlockClassFlags & SectionDescriptor.CLASS_REPLACEABLE) == 0) {
            noteDescriptorColumnReject(2L);
            return false;
        }
        if ((chunkBlockClassFlags & (SectionDescriptor.CLASS_TREE_SOIL | SectionDescriptor.CLASS_SURFACE_CANDIDATE)) == 0) {
            noteDescriptorColumnReject(2L);
            return false;
        }
        if (!originDescriptor.mayContainTreeVolume()) {
            noteDescriptorSectionReject(2L);
            return false;
        }
        int localX = x & 15;
        int localZ = z & 15;
        if (!originDescriptor.columnMayContainTreeVolume(localX, localZ)) {
            noteDescriptorColumnReject(2L);
            return false;
        }

        SectionDescriptor supportDescriptor = scratch.descriptors.findByBlockPos(x, y - 1, z);
        if (supportDescriptor == null) {
            return true;
        }
        if (!supportDescriptor.maySupportTreeBase()) {
            noteDescriptorSectionReject(2L);
            return false;
        }
        if (!supportDescriptor.columnMaySupportTreeBase(localX, localZ)) {
            noteDescriptorColumnReject(2L);
            return false;
        }
        return true;
    }

    private static boolean passesDescriptorGate(Feature<?> feature, DecorationPipelineScratch scratch, int x, int y, int z) {
        int gate = descriptorGateForFeature(feature);
        if (gate == GATE_NONE) {
            return true;
        }
        return passesDescriptorGate(gate, feature, scratch, x, y, z);
    }

    private static boolean passesDescriptorGate(int gate, Feature<?> feature, DecorationPipelineScratch scratch, int x, int y, int z) {
        if (gate == GATE_NONE) {
            return true;
        }

        SectionDescriptor descriptor = scratch.descriptors.findByBlockPos(x, y, z);
        if (descriptor == null) {
            return true;
        }

        int localX = x & 15;
        int localZ = z & 15;
        return switch (gate) {
            case GATE_ORE -> {
                if (!descriptor.hasOreTarget && !descriptor.hasStoneLike) {
                    noteDescriptorSectionReject(1L);
                    yield false;
                }
                if (!descriptor.columnHasBlockClassFlag(localX, localZ, SectionDescriptor.CLASS_ORE_TARGET | SectionDescriptor.CLASS_STONE_LIKE)) {
                    noteDescriptorColumnReject(1L);
                    yield false;
                }
                yield true;
            }
            case GATE_PLANT -> passesPlantDescriptorGate(scratch, x, y, z);
            case GATE_WATER_PLANT -> {
                if (!descriptor.hasWater) {
                    noteDescriptorSectionReject(1L);
                    yield false;
                }
                if (!descriptor.columnHasPaletteFlag(localX, localZ, SectionDescriptor.PALETTE_WATER)) {
                    noteDescriptorColumnReject(1L);
                    yield false;
                }
                yield true;
            }
            case GATE_TREE -> passesTreeDescriptorGate(scratch, x, y, z);
            case GATE_STONE_OR_DIRT -> {
                if (!descriptor.hasStoneLike && !descriptor.hasDirtLike) {
                    noteDescriptorSectionReject(1L);
                    yield false;
                }
                if (!descriptor.columnHasBlockClassFlag(localX, localZ, SectionDescriptor.CLASS_STONE_LIKE | SectionDescriptor.CLASS_DIRT_LIKE)) {
                    noteDescriptorColumnReject(1L);
                    yield false;
                }
                yield true;
            }
            case GATE_CAVE_SOLID -> {
                if (!descriptor.hasAir || (!descriptor.hasStoneLike && !descriptor.hasDirtLike)) {
                    noteDescriptorSectionReject(2L);
                    yield false;
                }
                if (!descriptor.columnHasPaletteFlag(localX, localZ, SectionDescriptor.PALETTE_AIR)
                        || !descriptor.columnHasBlockClassFlag(localX, localZ, SectionDescriptor.CLASS_STONE_LIKE | SectionDescriptor.CLASS_DIRT_LIKE)) {
                    noteDescriptorColumnReject(2L);
                    yield false;
                }
                yield true;
            }
            default -> true;
        };
    }

    static int descriptorGateForFeature(Feature<?> feature) {
        if (feature instanceof OreFeature || feature instanceof ScatteredOreFeature) {
            return GATE_ORE;
        }
        if (feature instanceof KelpFeature || feature instanceof SeagrassFeature || feature instanceof SeaPickleFeature
                || feature instanceof WaterloggedVegetationPatchFeature) {
            return GATE_WATER_PLANT;
        }
        if (feature instanceof TreeFeature) {
            return GATE_TREE;
        }
        if (feature instanceof MonsterRoomFeature) {
            return GATE_CAVE_SOLID;
        }
        if (feature instanceof GeodeFeature || feature instanceof MultifaceGrowthFeature
                || feature instanceof DripstoneClusterFeature || feature instanceof LargeDripstoneFeature
                || feature instanceof PointedDripstoneFeature) {
            return GATE_STONE_OR_DIRT;
        }
        if (feature instanceof SimpleBlockFeature || feature instanceof RandomPatchFeature
                || feature instanceof VegetationPatchFeature) {
            return GATE_PLANT;
        }
        return GATE_NONE;
    }

    private static int lakeIndex(int x, int z, int y) {
        return ((x << 4) + z) * 8 + y;
    }

    private static boolean hasLakeNeighbour(boolean[] mask, int x, int z, int y) {
        return (x < 15 && mask[lakeIndex(x + 1, z, y)])
                || (x > 0 && mask[lakeIndex(x - 1, z, y)])
                || (z < 15 && mask[lakeIndex(x, z + 1, y)])
                || (z > 0 && mask[lakeIndex(x, z - 1, y)])
                || (y < 7 && mask[lakeIndex(x, z, y + 1)])
                || (y > 0 && mask[lakeIndex(x, z, y - 1)]);
    }

    private static boolean lakeCanReplaceBlock(BlockState state) {
        return !state.is(BlockTags.FEATURES_CANNOT_REPLACE);
    }

    private static boolean canSpreadSculkFrom(WorldGenLevel level, BlockPos pos, BlockPos.MutableBlockPos neighborPos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof SculkBehaviour) {
            return true;
        }
        if (!state.isAir() && (!state.is(Blocks.WATER) || !state.getFluidState().isSource())) {
            return false;
        }

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        for (Direction direction : Direction.values()) {
            neighborPos.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
            if (level.getBlockState(neighborPos).isCollisionShapeFullBlock(level, neighborPos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean passesBiomeFilter(
            PlacedFeature feature,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z
    ) {
        if (feature == null) {
            return false;
        }
        Holder<Biome> biome = context.level().getBiome(scratch.mutablePos.set(x, y, z));
        return scratch.biomeFeatureCache.hasFeature(context.generator(), biome, feature);
    }

    private static SectionDescriptorCache activeDescriptors(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch
    ) {
        return scratch.descriptorsPreparedFor(context.chunk()) ? scratch.descriptors : null;
    }

    private static int fastHeight(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            Heightmap.Types type,
            int x,
            int z
    ) {
        WorldGenLevel level = context.level();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int descriptorHeight = scratch.descriptors.firstAvailableHeight(chunkX, chunkZ, type, x & 15, z & 15);
        if (descriptorHeight != Integer.MIN_VALUE) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_HEIGHTMAP_HITS);
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED);
            return descriptorHeight;
        }
        try {
            ChunkAccess chunk = chunkX == context.chunkX() && chunkZ == context.chunkZ()
                    ? context.chunk()
                    : level.getChunk(chunkX, chunkZ);
            Heightmap heightmap = scratch.cachedHeightmap(chunk, type);
            if (heightmap != null) {
                return heightmap.getFirstAvailable(x & 15, z & 15);
            }
        } catch (RuntimeException ignored) {
            // Modded worldgen contexts can virtualize chunk access.
        }
        return level.getHeight(type, x, z);
    }

    private static boolean descriptorHasWaterAt(DecorationPipelineScratch scratch, int x, int y, int z, boolean useTopWaterHeight) {
        if (useTopWaterHeight) {
            int topWaterY = scratch.descriptors.topWaterHeight(x >> 4, z >> 4, x & 15, z & 15);
            if (topWaterY != Integer.MIN_VALUE && topWaterY < y) {
                return false;
            }
        }
        SectionDescriptor descriptor = scratch.descriptors.findByBlockPos(x, y, z);
        return descriptor == null || descriptor.columnHasWaterAt(x, y, z);
    }

    private static boolean descriptorHasExactWaterAt(DecorationPipelineScratch scratch, int x, int y, int z) {
        SectionDescriptor descriptor = scratch.descriptors.findByBlockPos(x, y, z);
        return descriptor != null && descriptor.columnHasWaterAt(x, y, z);
    }

    private static void noteDescriptorSectionReject(long avoidedReads) {
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_SECTION_REJECTS);
        DecorationPipelineMetrics.add(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED, avoidedReads);
    }

    private static void noteDescriptorColumnReject(long avoidedReads) {
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_COLUMN_REJECTS);
        DecorationPipelineMetrics.add(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED, avoidedReads);
    }

    private static void noteSelectorFallbackReason(DecorationKernelPlan kernel) {
        int counter = kernel.selectorFallbackMetricCounter();
        if (counter >= 0) {
            DecorationPipelineMetrics.increment(counter);
        }
    }

    private static boolean setBlockTracked(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            BlockPos pos,
            BlockState state,
            int flags
    ) {
        WorldGenLevel level = context.level();
        boolean changed = level.setBlock(pos, state, flags);
        if (changed) {
            boolean trackDescriptors = scratch.hasPreparedDescriptors();
            boolean trackWorkspace = DecorationWorkspaceBridge.hasCurrentWorkspace(context.workspace());
            if (!trackDescriptors && !trackWorkspace) {
                return true;
            }
            ChunkAccess chunk = chunkFor(context, pos.getX(), pos.getZ());
            if (trackWorkspace) {
                DecorationWorkspaceBridge.mirrorCurrentWorkspaceWrite(chunk, pos, state);
            }
            noteBlockMutation(chunk, scratch, pos.getX(), pos.getY(), pos.getZ(), trackDescriptors, trackWorkspace);
        }
        return changed;
    }

    private static void setChunkWriterBlockTracked(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            BlockPos pos,
            BlockState state
    ) {
        scratch.chunkWriter.setBlockState(pos, state);
        boolean trackDescriptors = scratch.hasPreparedDescriptors();
        boolean trackWorkspace = DecorationWorkspaceBridge.hasCurrentWorkspace(context.workspace());
        if (trackDescriptors || trackWorkspace) {
            ChunkAccess chunk = chunkFor(context, pos.getX(), pos.getZ());
            if (trackWorkspace) {
                DecorationWorkspaceBridge.mirrorCurrentWorkspaceWrite(chunk, pos, state);
            }
            noteBlockMutation(chunk, scratch, pos.getX(), pos.getY(), pos.getZ(), trackDescriptors, trackWorkspace);
        }
    }

    private static void setChunkWriterBlockJournaled(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            ChunkAccess chunk,
            BlockPos pos,
            BlockState state
    ) {
        scratch.chunkWriter.setBlockState(pos, state);
        if (DecorationWorkspaceBridge.hasCurrentWorkspace(context.workspace())) {
            DecorationWorkspaceBridge.mirrorCurrentWorkspaceWrite(chunk, pos, state);
        }
        if (scratch.hasPreparedDescriptors()) {
            scratch.noteJournalMutation(chunk, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private static void noteBlockMutation(
            ChunkAccess chunk,
            DecorationPipelineScratch scratch,
            int blockX,
            int blockY,
            int blockZ,
            boolean trackDescriptors,
            boolean trackWorkspace
    ) {
        if (trackDescriptors) {
            scratch.descriptors.noteBlockMutation(chunk, blockX, blockY, blockZ);
        }
        if (trackWorkspace) {
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORKSPACE_DESCRIPTOR_REPAIRS);
        }
    }

    private static int opcodeFor(PlacementModifier modifier) {
        if (modifier instanceof InSquarePlacement) return IN_SQUARE;
        if (modifier instanceof HeightRangePlacement && modifier instanceof GA$HeightRangePlacementAccess) return HEIGHT_RANGE;
        if (modifier instanceof HeightmapPlacement && modifier instanceof GA$HeightmapPlacementAccess) return HEIGHTMAP;
        if (modifier instanceof RandomOffsetPlacement && modifier instanceof GA$RandomOffsetPlacementAccess) return RANDOM_OFFSET;
        if (modifier instanceof RepeatingPlacement && modifier instanceof GA$RepeatingPlacementAccess) return REPEATING;
        if (modifier instanceof BiomeFilter) return BIOME_FILTER;
        if (modifier instanceof PlacementFilter && modifier instanceof GA$PlacementFilterAccess) return PLACEMENT_FILTER;
        if (modifier instanceof GA$PlacementModifierExtension extension && extension.ga$hasFastPositions()) return FAST_POSITIONS;
        return VANILLA_MODIFIER;
    }

    private record CompiledBlockColumn(
            BlockColumnConfiguration.Layer[] layers,
            Direction direction,
            net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate allowedPlacement,
            boolean prioritizeTip
    ) {
    }
}
