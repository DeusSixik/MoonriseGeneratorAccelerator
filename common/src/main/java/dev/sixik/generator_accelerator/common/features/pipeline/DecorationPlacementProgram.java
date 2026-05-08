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
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import dev.sixik.generator_accelerator.common.features.pipeline.ore.OreTargetPlan;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
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
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SculkPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.BitSet;
import java.util.stream.Stream;

final class DecorationPlacementProgram {
    private static final BlockState SEAGRASS_STATE = Blocks.SEAGRASS.defaultBlockState();
    private static final BlockState TALL_SEAGRASS_LOWER = Blocks.TALL_SEAGRASS.defaultBlockState();
    private static final BlockState TALL_SEAGRASS_UPPER = TALL_SEAGRASS_LOWER.setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER);
    private static final BlockState ICE_STATE = Blocks.ICE.defaultBlockState();
    private static final BlockState SNOW_STATE = Blocks.SNOW.defaultBlockState();
    private static final BlockState KELP_PLANT = Blocks.KELP_PLANT.defaultBlockState();
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
    private static final ThreadLocal<IdentityHashMap<BlockColumnConfiguration, CompiledBlockColumn>> BLOCK_COLUMN_CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);
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
                int height = fastHeight(context.level(), this.heightmapTypes[opIndex], x, z);
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
        if (!context.level().ensureCanWrite(pos)) {
            return false;
        }
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
                return placeScatteredOreNative(oreConfiguration, oreTargetPlan, context, scratch, x, y, z);
            }
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
        if (kernel.kind() == DecorationKernelKind.PARTIAL_NATIVE_DESCRIPTOR_GATED) {
            if (!passesDescriptorGate(feature, scratch, x, y, z)) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.PARTIAL_NATIVE_DESCRIPTOR_REJECTED_CALLS);
                return false;
            }
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
        if (nestedConfigured == null || nestedProgram == null) {
            return null;
        }
        if (nestedConfigured.feature() != Feature.SIMPLE_BLOCK || !(nestedConfigured.config() instanceof SimpleBlockConfiguration)) {
            return null;
        }

        int spreadXZ = config.xzSpread() + 1;
        int spreadY = config.ySpread() + 1;
        int tries = config.tries();
        boolean useBatch = tries >= SIMPLE_BLOCK_BATCH_TRY_THRESHOLD;
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
                if (nestedProgram.executeConfigured(kernel, context, scratch, placementContext, nestedConfigured, candidateX, candidateY, candidateZ)) {
                    placedAny = true;
                }
            }
        } finally {
            placementContext.set(context.level(), context.generator(), previousTopFeature);
            DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_CANDIDATE_NANOS, candidateStart);
        }
        return useBatch ? flushSimpleBlockBatch(context, scratch) : placedAny;
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
        if (nestedConfigured == null || nestedProgram == null || selectorPlan == null) {
            return null;
        }
        FeatureConfiguration nestedConfig = nestedConfigured.config();
        Feature<?> nestedFeature = nestedConfigured.feature();
        if ((nestedFeature != Feature.RANDOM_SELECTOR || !(nestedConfig instanceof RandomFeatureConfiguration))
                && (nestedFeature != Feature.RANDOM_BOOLEAN_SELECTOR || !(nestedConfig instanceof RandomBooleanFeatureConfiguration))
                && (nestedFeature != Feature.SIMPLE_RANDOM_SELECTOR || !(nestedConfig instanceof SimpleRandomFeatureConfiguration))) {
            return null;
        }

        int spreadXZ = config.xzSpread() + 1;
        int spreadY = config.ySpread() + 1;
        int tries = config.tries();
        boolean useBatch = tries >= SIMPLE_BLOCK_BATCH_TRY_THRESHOLD;
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
                if (nestedProgram.executeConfigured(kernel, context, scratch, placementContext, nestedConfigured, candidateX, candidateY, candidateZ)) {
                    placedAny = true;
                }
            }
        } finally {
            placementContext.set(context.level(), context.generator(), previousTopFeature);
            DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_CANDIDATE_NANOS, candidateStart);
        }
        return useBatch ? flushSimpleBlockBatch(context, scratch) : placedAny;
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
        FeatureConfiguration config = configuredFeature.config();
        Feature<?> feature = configuredFeature.feature();
        if ((feature != Feature.RANDOM_SELECTOR || !(config instanceof RandomFeatureConfiguration))
                && (feature != Feature.RANDOM_BOOLEAN_SELECTOR || !(config instanceof RandomBooleanFeatureConfiguration))
                && (feature != Feature.SIMPLE_RANDOM_SELECTOR || !(config instanceof SimpleRandomFeatureConfiguration))) {
            return null;
        }

        return switch (selectorPlan.mode()) {
            case SelectorPlan.MODE_RANDOM_FEATURE -> placeRandomSelectorNative(selectorPlan, kernel, context, scratch, placementContext, x, y, z);
            case SelectorPlan.MODE_RANDOM_BOOLEAN -> placeRandomBooleanSelectorNative(selectorPlan, kernel, context, scratch, placementContext, x, y, z);
            case SelectorPlan.MODE_SIMPLE_RANDOM -> placeSimpleRandomSelectorNative(selectorPlan, kernel, context, scratch, placementContext, x, y, z);
            default -> null;
        };
    }

    private static boolean placeRandomSelectorNative(
            SelectorPlan selectorPlan,
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        DecorationKernelPlan[] branchKernels = selectorPlan.branchKernels();
        float[] chances = selectorPlan.branchChances();
        RandomSource random = context.random();
        int branchIndex = branchKernels.length - 1;
        for (int i = 0; i < chances.length; i++) {
            if (random.nextFloat() < chances[i]) {
                branchIndex = i;
                break;
            }
        }
        return executeSelectorBranch(branchKernels, branchIndex, context, scratch, placementContext, x, y, z);
    }

    private static boolean placeRandomBooleanSelectorNative(
            SelectorPlan selectorPlan,
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        int branchIndex = context.random().nextBoolean() ? 0 : 1;
        return executeSelectorBranch(
                selectorPlan.branchKernels(),
                branchIndex,
                context,
                scratch,
                placementContext,
                x,
                y,
                z
        );
    }

    private static boolean placeSimpleRandomSelectorNative(
            SelectorPlan selectorPlan,
            DecorationKernelPlan kernel,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        DecorationKernelPlan[] branchKernels = selectorPlan.branchKernels();
        int branchCount = branchKernels.length;
        if (branchCount == 0) {
            return false;
        }
        int branchIndex = context.random().nextInt(branchCount);
        return executeSelectorBranch(
                branchKernels,
                branchIndex,
                context,
                scratch,
                placementContext,
                x,
                y,
                z
        );
    }

    private static boolean executeSelectorBranch(
            DecorationKernelPlan[] branchKernels,
            int branchIndex,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext,
            int x,
            int y,
            int z
    ) {
        if (branchIndex < 0 || branchIndex >= branchKernels.length) {
            return false;
        }
        DecorationKernelPlan branchKernel = branchKernels[branchIndex];
        if (branchKernel == null || branchKernel.placementProgram() == null || branchKernel.configuredFeature() == null) {
            return false;
        }
        ConfiguredFeature<?, ?> branchConfiguredFeature = branchKernel.configuredFeature().value();
        return branchConfiguredFeature != null
                && branchKernel.placementProgram().executeConfigured(branchKernel, context, scratch, placementContext, branchConfiguredFeature, x, y, z);
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
                        commitOrePlacement(cachedSection, cachedRaw, target, currentStateId, airStates, targetPlan.placementMayBeAir(), localX, localY, localZ, sectionIndex);
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

        if (!oreHasSurfaceBelow(context.level(), startX, startY, startZ, width)) {
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

    private static boolean oreHasSurfaceBelow(WorldGenLevel level, int startX, int startY, int startZ, int width) {
        ChunkAccess cachedChunk = null;
        Heightmap cachedHeightmap = null;
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;

        for (int x = startX; x <= startX + width; x++) {
            for (int z = startZ; z <= startZ + width; z++) {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                    cachedChunk = level.getChunk(chunkX, chunkZ);
                    cachedHeightmap = ((ChunkAccess$getOrCreateHeightmapUnsynchronized) cachedChunk)
                            .bts$getOrCreateHeightmapUnsynchronized(Heightmap.Types.OCEAN_FLOOR_WG);
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
        BitSet visited = scratch.clearOreBitSet();
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
                            if (visited.get(bitIndex)) {
                                continue;
                            }
                            visited.set(bitIndex);

                            int secX = blockX >> 4;
                            if (secX != lastSecX || secY != lastSecY || secZ != lastSecZ) {
                                pos.set(blockX, blockY, blockZ);
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
                            if (DecorationPipelineMetrics.ENABLED) {
                                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_READS);
                                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_GENERATED);
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
                                            cachedSection,
                                            cachedRaw,
                                            target,
                                            currentStateId,
                                            airStates,
                                            targetPlan.placementMayBeAir(),
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

        return placedCount > 0;
    }

    private static int scatteredOreSpread(RandomSource random, int spread) {
        return Math.round((random.nextFloat() - random.nextFloat()) * (float) spread);
    }

    private static boolean shouldSkipAirCheck(RandomSource random, float chance) {
        return chance <= 0.0F || (chance < 1.0F && random.nextFloat() >= chance);
    }

    private static void commitOrePlacement(
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
        long start = DecorationPipelineMetrics.startTimer();
        if (raw != null && (!placementMayBeAir || airStates[previousStateId] == airStates[target.placementStateId()])) {
            raw[sectionIndex] = target.placementStateId();
        } else {
            section.setBlockState(localX, localY, localZ, target.placementState(), false);
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
            int y = fastHeight(context.level(), Heightmap.Types.OCEAN_FLOOR, x, z);
            pos.set(x, y, z);

            BlockState state = SEA_PICKLES[context.random().nextInt(4)];
            if (context.level().getBlockState(pos).is(Blocks.WATER) && state.canSurvive(context.level(), pos)) {
                context.level().setBlock(pos, state, 2);
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

        level.setBlock(pos, config.state.createLegacyBlock(), 2);
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
                        scratch.chunkWriter.setBlockState(pos, state);
                        markAboveForPostProcessing(scratch, markPos.set(pos));
                    } else {
                        context.level().setBlock(pos, state, 2);
                        markAboveForPostProcessing(context.level(), markPos.set(pos));
                    }
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
                    placedAny = true;
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
        CompiledBlockColumn compiled = BLOCK_COLUMN_CACHE.get().computeIfAbsent(config, DecorationPlacementProgram::compileBlockColumn);
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
                context.level().setBlock(placePos, layer.state().getState(context.random(), placePos), 2);
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
        int y = fastHeight(context.level(), Heightmap.Types.OCEAN_FLOOR, x, z);
        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(x, y, z);
        if (!context.level().ensureCanWrite(pos)) {
            return false;
        }
        if (!context.level().getBlockState(pos).is(Blocks.WATER)) {
            return false;
        }

        boolean tall = context.random().nextDouble() < config.probability;
        BlockState state = tall ? TALL_SEAGRASS_LOWER : SEAGRASS_STATE;
        if (!state.canSurvive(context.level(), pos)) {
            return false;
        }

        if (tall) {
            BlockPos.MutableBlockPos above = scratch.secondMutablePos.set(x, y + 1, z);
            if (!context.level().getBlockState(above).is(Blocks.WATER)) {
                return false;
            }
            context.level().setBlock(pos, state, 2);
            context.level().setBlock(above, TALL_SEAGRASS_UPPER, 2);
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_WRITES, 2L);
            return true;
        }

        context.level().setBlock(pos, state, 2);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
        return true;
    }

    private static boolean placeKelpNative(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int z
    ) {
        int y = fastHeight(context.level(), Heightmap.Types.OCEAN_FLOOR, x, z);
        BlockPos.MutableBlockPos pos = scratch.mutablePos.set(x, y, z);
        if (!context.level().ensureCanWrite(pos)) {
            return false;
        }
        if (!context.level().getBlockState(pos).is(Blocks.WATER)) {
            return false;
        }

        BlockPos.MutableBlockPos temp = scratch.secondMutablePos;
        int maxHeight = 1 + context.random().nextInt(10);
        int placedHeads = 0;
        for (int step = 0; step <= maxHeight; step++) {
            int posY = pos.getY();
            temp.set(x, posY + 1, z);
            if (context.level().getBlockState(pos).is(Blocks.WATER)
                    && context.level().getBlockState(temp).is(Blocks.WATER)
                    && KELP_PLANT.canSurvive(context.level(), pos)) {
                if (step == maxHeight) {
                    context.level().setBlock(pos, KELP_HEADS[context.random().nextInt(4)], 2);
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
                    placedHeads++;
                    break;
                }

                context.level().setBlock(pos, KELP_PLANT, 2);
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
            } else if (step > 0) {
                int belowY = posY - 1;
                temp.set(x, belowY, z);
                if (KELP_HEADS[0].canSurvive(context.level(), temp)) {
                    temp.set(x, belowY - 1, z);
                    if (!context.level().getBlockState(temp).is(Blocks.KELP)) {
                        temp.set(x, belowY, z);
                        context.level().setBlock(temp, KELP_HEADS[context.random().nextInt(4)], 2);
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
                int y = fastHeight(level, Heightmap.Types.MOTION_BLOCKING, x, z);
                top.set(x, y, z);
                below.set(x, y - 1, z);
                Biome biome = level.getBiome(top).value();

                if (biome.shouldFreeze(level, below, false)) {
                    scratch.chunkWriter.setBlockState(below, ICE_STATE);
                    writes++;
                }
                if (biome.shouldSnow(level, top)) {
                    scratch.chunkWriter.setBlockState(top, SNOW_STATE);
                    writes++;
                    BlockState belowState = scratch.chunkWriter.getBlockState(below);
                    if (belowState.hasProperty(SnowyDirtBlock.SNOWY)) {
                        scratch.chunkWriter.setBlockState(below, belowState.setValue(SnowyDirtBlock.SNOWY, true));
                        writes++;
                    }
                }
            }
        }

        DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_WRITES, writes);
        return true;
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
        BlockPos origin = new BlockPos(x, y, z);
        BlockPos.MutableBlockPos candidatePos = scratch.mutablePos.set(x, y, z);
        BlockPos.MutableBlockPos probe = scratch.secondMutablePos;
        if (!canSpreadSculkFrom(level, origin, probe)) {
            return false;
        }

        RandomSource random = context.random();
        SculkSpreader spreader = SculkSpreader.createWorldGenSpreader();
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
                    && level.setBlock(origin, Blocks.SCULK_CATALYST.defaultBlockState(), 3)) {
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

            if (level.setBlock(
                    candidatePos,
                    Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(SculkShriekerBlock.CAN_SUMMON, true),
                    3
            )) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
            }
        }
        return true;
    }

    private static boolean flushSimpleBlockBatch(
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch
    ) {
        int count = scratch.candidateCount;
        if (count <= 0) {
            scratch.finishSimpleBlockBatch();
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
                scratch.chunkWriter.begin(chunkFor(
                        context,
                        scratch.candidateX[firstCandidate],
                        scratch.candidateZ[firstCandidate]
                ));
                for (int bucket = firstSectionBucket; bucket >= 0; bucket = scratch.sectionBucketNextInChunk[bucket]) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_SECTION_BATCHES);
                    for (int candidate = scratch.sectionBucketHead[bucket]; candidate >= 0; candidate = scratch.candidateNext[candidate]) {
                        if (placeSimpleBlockPrepared(
                                scratch.candidateSimpleBlockState[candidate],
                                context,
                                scratch,
                                scratch.candidateX[candidate],
                                scratch.candidateY[candidate],
                                scratch.candidateZ[candidate]
                        )) {
                            placedAny = true;
                        }
                    }
                }
            }
        } finally {
            DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_COMMIT_NANOS, commitStart);
            scratch.finishSimpleBlockBatch();
        }
        return placedAny;
    }

    private static boolean placeSimpleBlockPrepared(
            BlockState state,
            DecorationPipelineExecutor.ExecutionContext context,
            DecorationPipelineScratch scratch,
            int x,
            int y,
            int z
    ) {
        BlockPos.MutableBlockPos origin = scratch.mutablePos.set(x, y, z);
        if (!context.level().ensureCanWrite(origin)) {
            return false;
        }
        if (!state.canSurvive(context.level(), origin)) {
            return false;
        }

        if (state.getBlock() instanceof DoublePlantBlock) {
            BlockPos.MutableBlockPos above = scratch.secondMutablePos.set(x, y + 1, z);
            if (!context.level().ensureCanWrite(above) || !scratch.chunkWriter.getBlockState(above).isAir()) {
                return false;
            }
            scratch.chunkWriter.setBlockState(origin, state);
            scratch.chunkWriter.setBlockState(above, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.WORLD_BLOCK_WRITES, 2L);
            return true;
        }

        scratch.chunkWriter.setBlockState(origin, state);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_WRITES);
        return true;
    }

    private static ChunkAccess chunkFor(
            DecorationPipelineExecutor.ExecutionContext context,
            int x,
            int z
    ) {
        ChunkAccess centerChunk = context.chunk();
        if ((x >> 4) == centerChunk.getPos().x && (z >> 4) == centerChunk.getPos().z) {
            return centerChunk;
        }
        return context.level().getChunk(x >> 4, z >> 4);
    }

    private static boolean passesPlantDescriptorGate(DecorationPipelineScratch scratch, int x, int y, int z) {
        SectionDescriptor descriptor = scratch.descriptors.findByBlockPos(x, y, z);
        return descriptor == null || descriptor.hasSurfaceCandidate || descriptor.hasDirtLike || descriptor.hasAir;
    }

    private static boolean passesSpringDescriptorGate(DecorationPipelineScratch scratch, int x, int y, int z) {
        SectionDescriptor descriptor = scratch.descriptors.findByBlockPos(x, y, z);
        return descriptor == null || descriptor.hasAir || descriptor.hasStoneLike || descriptor.hasDirtLike;
    }

    private static boolean passesTreeDescriptorGate(DecorationPipelineScratch scratch, int x, int y, int z) {
        SectionDescriptor originDescriptor = scratch.descriptors.findByBlockPos(x, y, z);
        if (originDescriptor == null) {
            return true;
        }
        if (!originDescriptor.mayContainTreeVolume()) {
            return false;
        }

        SectionDescriptor supportDescriptor = scratch.descriptors.findByBlockPos(x, y - 1, z);
        return supportDescriptor == null || supportDescriptor.maySupportTreeBase();
    }

    private static boolean passesDescriptorGate(Feature<?> feature, DecorationPipelineScratch scratch, int x, int y, int z) {
        int gate = descriptorGate(feature);
        if (gate == GATE_NONE) {
            return true;
        }

        SectionDescriptor descriptor = scratch.descriptors.findByBlockPos(x, y, z);
        if (descriptor == null) {
            return true;
        }

        return switch (gate) {
            case GATE_ORE -> descriptor.hasOreTarget || descriptor.hasStoneLike;
            case GATE_PLANT -> descriptor.hasSurfaceCandidate || descriptor.hasDirtLike || descriptor.hasAir;
            case GATE_WATER_PLANT -> descriptor.hasWater;
            case GATE_TREE -> passesTreeDescriptorGate(scratch, x, y, z);
            case GATE_STONE_OR_DIRT -> descriptor.hasStoneLike || descriptor.hasDirtLike;
            case GATE_CAVE_SOLID -> descriptor.hasAir && (descriptor.hasStoneLike || descriptor.hasDirtLike);
            default -> true;
        };
    }

    private static int descriptorGate(Feature<?> feature) {
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

    private static int fastHeight(WorldGenLevel level, Heightmap.Types type, int x, int z) {
        try {
            ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
            Heightmap heightmap = ((ChunkAccess$getOrCreateHeightmapUnsynchronized) chunk).bts$getOrCreateHeightmapUnsynchronized(type);
            if (heightmap != null) {
                return heightmap.getFirstAvailable(x & 15, z & 15);
            }
        } catch (RuntimeException ignored) {
            // Modded worldgen contexts can virtualize chunk access.
        }
        return level.getHeight(type, x, z);
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
