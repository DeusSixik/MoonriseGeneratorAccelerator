package dev.sixik.generator_accelerator.common.surface_compiler.backend.interpreter;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionContext;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;

public final class MaskInterpreterBackend {
    private static final Constructor<SurfaceRules.Context> CONTEXT_CTOR = contextConstructor();
    private static final Method UPDATE_XZ = contextMethod("updateXZ", int.class, int.class);
    private static final Method UPDATE_Y = contextMethod("updateY", int.class, int.class, int.class, int.class, int.class, int.class);
    private static final Field DEFAULT_BLOCK = defaultBlockField();

    public boolean canExecute(SurfaceExecutionPlan plan) {
        return plan != null
                && plan.ir() != null
                && plan.facts() != null
                && supportsMaterialWrites(plan)
                && plan.facts().safeForInterpreter()
                && (plan.tier() == SurfaceTier.MASK_INTERPRETER || plan.tier() == SurfaceTier.GUARDED_HYBRID_JIT || plan.tier() == SurfaceTier.VALIDATION);
    }

    public boolean supportsMaterialWrites(SurfaceExecutionPlan plan) {
        return CONTEXT_CTOR != null
                && UPDATE_XZ != null
                && UPDATE_Y != null
                && DEFAULT_BLOCK != null
                && plan != null
                && plan.ir() != null
                && !plan.ir().hasUnsafeOrMutatingOp();
    }

    public boolean execute(SurfaceExecutionPlan plan, SurfaceExecutionContext context) {
        if (!canExecute(plan)) {
            return false;
        }
        try {
            boolean executed = executeVanillaRuleWithCow(plan, context);
            if (executed) {
                for (SurfaceOp op : plan.ir().ops()) {
                    if (op.isStateful()) {
                        context.workerState().trace().record(op.opcode(), op.domain().name(), op.stateIn(), op.stateOut());
                    }
                }
            }
            return executed;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private boolean executeVanillaRuleWithCow(SurfaceExecutionPlan plan, SurfaceExecutionContext execution) throws ReflectiveOperationException {
        SurfaceSystem surfaceSystem = execution.surfaceSystem();
        RandomState randomState = execution.randomState();
        BiomeManager biomeManager = execution.biomeManager();
        Registry<Biome> biomeRegistry = execution.biomeRegistry();
        WorldGenerationContext worldContext = execution.worldContext();
        ChunkAccess chunk = execution.chunk();
        NoiseChunk noiseChunk = execution.noiseChunk();
        SurfaceRules.RuleSource ruleSource = execution.ruleSource();

        ChunkPos chunkPos = chunk.getPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();
        Function<BlockPos, Holder<Biome>> biomeGetter = biomeManager::getBiome;
        SurfaceRules.Context surfaceContext = CONTEXT_CTOR.newInstance(surfaceSystem, randomState, chunk, noiseChunk, biomeGetter, biomeRegistry, worldContext);
        SurfaceRules.SurfaceRule surfaceRule = ruleSource.apply(surfaceContext);
        BlockState defaultBlock = (BlockState) DEFAULT_BLOCK.get(surfaceSystem);
        LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinBuildHeight();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int globalX = minBlockX + localX;
                int globalZ = minBlockZ + localZ;
                int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) + 1;
                Holder<Biome> biome = biomeManager.getBiome(biomePos.set(globalX, execution.useLegacyRandomSource() ? 0 : surfaceY, globalZ));
                if (requiresVanillaExtension(biome)) {
                    return false;
                }

                int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) + 1;
                UPDATE_XZ.invoke(surfaceContext, globalX, globalZ);
                int stoneDepthAbove = 0;
                int waterHeight = Integer.MIN_VALUE;
                int minSurface = Integer.MAX_VALUE;

                for (int y = topY; y >= minY; y--) {
                    BlockState current = getBlock(chunk, sections, localX, y, localZ);
                    if (current.isAir()) {
                        stoneDepthAbove = 0;
                        waterHeight = Integer.MIN_VALUE;
                        continue;
                    }
                    if (!current.getFluidState().isEmpty()) {
                        if (waterHeight == Integer.MIN_VALUE) {
                            waterHeight = y + 1;
                        }
                        continue;
                    }
                    if (minSurface >= y) {
                        minSurface = DimensionType.WAY_BELOW_MIN_Y;
                        for (int belowY = y - 1; belowY >= minY - 1; belowY--) {
                            if (!isDefaultStone(defaultBlock, getBlock(chunk, sections, localX, belowY, localZ))) {
                                minSurface = belowY + 1;
                                break;
                            }
                        }
                    }
                    stoneDepthAbove++;
                    int stoneDepthBelow = y - minSurface + 1;
                    UPDATE_Y.invoke(surfaceContext, stoneDepthAbove, stoneDepthBelow, waterHeight, globalX, y, globalZ);
                    if (current != defaultBlock) {
                        continue;
                    }
                    BlockState applied = surfaceRule.tryApply(globalX, y, globalZ);
                    if (applied != null) {
                        execution.cowManager().writerForY(y).setBlockState(localX, y & 15, localZ, applied);
                    }
                }
            }
        }
        return true;
    }

    private static boolean requiresVanillaExtension(Holder<Biome> biome) {
        return biome.is(Biomes.ERODED_BADLANDS)
                || biome.is(Biomes.FROZEN_OCEAN)
                || biome.is(Biomes.DEEP_FROZEN_OCEAN);
    }

    private static BlockState getBlock(ChunkAccess chunk, LevelChunkSection[] sections, int localX, int y, int localZ) {
        int sectionIndex = chunk.getSectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState();
        }
        LevelChunkSection section = sections[sectionIndex];
        if (section == null) {
            return net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState();
        }
        return section.getBlockState(localX, y & 15, localZ);
    }

    private static boolean isDefaultStone(BlockState defaultBlock, BlockState state) {
        return state == defaultBlock || state.is(defaultBlock.getBlock());
    }

    private static Constructor<SurfaceRules.Context> contextConstructor() {
        try {
            Constructor<SurfaceRules.Context> constructor = SurfaceRules.Context.class.getDeclaredConstructor(
                    SurfaceSystem.class,
                    RandomState.class,
                    ChunkAccess.class,
                    NoiseChunk.class,
                    Function.class,
                    Registry.class,
                    WorldGenerationContext.class
            );
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Method contextMethod(String name, Class<?>... parameters) {
        try {
            Method method = SurfaceRules.Context.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Field defaultBlockField() {
        try {
            Field field = SurfaceSystem.class.getDeclaredField("defaultBlock");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
