package dev.sixik.generator_accelerator.common.structures.mixin.pools;

import com.mojang.logging.LogUtils;
import dev.sixik.generator_accelerator.common.structures.JigsawFreeSpaceTracker;
import dev.sixik.generator_accelerator.common.structures.JigsawPlacementHotPath;
import dev.sixik.generator_accelerator.common.structures.StructurePlacementShuffler;
import dev.sixik.generator_accelerator.common.structures.StructurePoolElementCache;
import dev.sixik.generator_accelerator.common.structures.StructureTemplatePoolCache;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.data.worldgen.Pools;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.SequencedPriorityIterator;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Optional;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class MixinJigsawPlacement$Placer$use_cached_jigsaws {
    @Unique
    private static final Logger GA$LOGGER = LogUtils.getLogger();

    @Unique
    private final Object2IntMap<ResourceKey<StructureTemplatePool>> ga$poolMaxSizeCache = ga$createPoolMaxSizeCache();

    @Shadow
    @Final
    private Registry<StructureTemplatePool> pools;

    @Shadow
    @Final
    private int maxDepth;

    @Shadow
    @Final
    private ChunkGenerator chunkGenerator;

    @Shadow
    @Final
    private StructureTemplateManager structureTemplateManager;

    @Shadow
    @Final
    private List<? super PoolElementStructurePiece> pieces;

    @Shadow
    @Final
    private RandomSource random;

    @Shadow
    @Final
    SequencedPriorityIterator<Object> placing;

    @Shadow
    private static ResourceKey<StructureTemplatePool> readPoolKey(StructureTemplate.StructureBlockInfo jigsaw, PoolAliasLookup aliasLookup) {
        throw new AssertionError();
    }

    /**
     * @author Sixik
     * @reason Collapse the remaining Jigsaw placement overhead: avoid concatenation lists, stream max scans,
     * repeated pool max-size resolution, and the second translated bounding-box lookup per candidate.
     */
    @Overwrite
    void tryPlacingChildren(
            PoolElementStructurePiece parentPiece,
            MutableObject<VoxelShape> freeShape,
            int depth,
            boolean useExpansionHack,
            LevelHeightAccessor heightAccessor,
            RandomState randomState,
            PoolAliasLookup aliasLookup,
            LiquidSettings liquidSettings
    ) {
        StructurePoolElement parentElement = parentPiece.getElement();
        BlockPos parentPos = parentPiece.getPosition();
        Rotation parentRotation = parentPiece.getRotation();
        StructureTemplatePool.Projection parentProjection = parentElement.getProjection();
        boolean parentRigid = parentProjection == StructureTemplatePool.Projection.RIGID;
        MutableObject<VoxelShape> rootFreeShape = ga$outerFreeShape(freeShape, parentPiece.getBoundingBox(), depth);
        MutableObject<VoxelShape> insideParentShape = null;
        BoundingBox parentBox = parentPiece.getBoundingBox();
        int parentMinY = parentBox.minY();

        List<StructureTemplate.StructureBlockInfo> parentJigsaws =
                ((StructurePoolElementCache) parentElement).bts$getCachedJigsawBlocks(
                        this.structureTemplateManager,
                        parentPos,
                        parentRotation,
                        this.random
                );

        outer:
        for (int parentIndex = 0, parentCount = parentJigsaws.size(); parentIndex < parentCount; parentIndex++) {
            StructureTemplate.StructureBlockInfo parentJigsaw = parentJigsaws.get(parentIndex);
            Direction frontFacing = JigsawBlock.getFrontFacing(parentJigsaw.state());
            int frontStepY = frontFacing.getStepY();
            BlockPos parentJigsawPos = parentJigsaw.pos();
            BlockPos childAnchorPos = parentJigsawPos.relative(frontFacing);
            int parentYOffset = parentJigsawPos.getY() - parentMinY;
            int[] surfaceHeight = {-1};

            ResourceKey<StructureTemplatePool> poolKey = readPoolKey(parentJigsaw, aliasLookup);
            Optional<Holder.Reference<StructureTemplatePool>> primaryHolder = this.pools.getHolder(poolKey);
            if (primaryHolder.isEmpty()) {
                GA$LOGGER.warn("Empty or non-existent pool: {}", poolKey.location());
                continue;
            }

            Holder.Reference<StructureTemplatePool> primaryReference = primaryHolder.get();
            StructureTemplatePool primaryPool = primaryReference.value();
            if (primaryPool.size() == 0 && !primaryReference.is(Pools.EMPTY)) {
                GA$LOGGER.warn("Empty or non-existent pool: {}", poolKey.location());
                continue;
            }

            Holder<StructureTemplatePool> fallbackHolder = primaryPool.getFallback();
            StructureTemplatePool fallbackPool = fallbackHolder.value();
            if (fallbackPool.size() == 0 && !fallbackHolder.is(Pools.EMPTY)) {
                GA$LOGGER.warn(
                        "Empty or non-existent fallback pool: {}",
                        fallbackHolder.unwrapKey().map(key -> key.location().toString()).orElse("<unregistered>")
                );
                continue;
            }

            MutableObject<VoxelShape> candidateFreeShape = rootFreeShape;
            if (parentBox.isInside(childAnchorPos)) {
                candidateFreeShape = insideParentShape;
                if (insideParentShape == null) {
                    insideParentShape = JigsawFreeSpaceTracker.createInnerFreeShape(parentBox);
                    candidateFreeShape = insideParentShape;
                }
            }

            int placementPriority = parentJigsaw.nbt() != null ? parentJigsaw.nbt().getInt("placement_priority") : 0;
            if (depth != this.maxDepth) {
                List<StructurePoolElement> primaryTemplates = ((StructureTemplatePoolCache) primaryPool).bts$getCachedShuffledTemplates(this.random);
                if (this.ga$tryTemplates(
                        primaryTemplates,
                        parentPiece,
                        parentJigsaw,
                        childAnchorPos,
                        parentProjection,
                        parentRigid,
                        parentMinY,
                        parentYOffset,
                        frontStepY,
                        candidateFreeShape,
                        depth,
                        useExpansionHack,
                        heightAccessor,
                        randomState,
                        aliasLookup,
                        liquidSettings,
                        placementPriority,
                        surfaceHeight
                )) {
                    continue;
                }
            }

            List<StructurePoolElement> fallbackTemplates = ((StructureTemplatePoolCache) fallbackPool).bts$getCachedShuffledTemplates(this.random);
            if (this.ga$tryTemplates(
                    fallbackTemplates,
                    parentPiece,
                    parentJigsaw,
                    childAnchorPos,
                    parentProjection,
                    parentRigid,
                    parentMinY,
                    parentYOffset,
                    frontStepY,
                    candidateFreeShape,
                    depth,
                    useExpansionHack,
                    heightAccessor,
                    randomState,
                    aliasLookup,
                    liquidSettings,
                    placementPriority,
                    surfaceHeight
            )) {
                continue outer;
            }
        }
    }

    @Unique
    private boolean ga$tryTemplates(
            List<StructurePoolElement> templates,
            PoolElementStructurePiece parentPiece,
            StructureTemplate.StructureBlockInfo parentJigsaw,
            BlockPos childAnchorPos,
            StructureTemplatePool.Projection parentProjection,
            boolean parentRigid,
            int parentMinY,
            int parentYOffset,
            int frontStepY,
            MutableObject<VoxelShape> freeShape,
            int depth,
            boolean useExpansionHack,
            LevelHeightAccessor heightAccessor,
            RandomState randomState,
            PoolAliasLookup aliasLookup,
            LiquidSettings liquidSettings,
            int placementPriority,
            int[] surfaceHeight
    ) {
        for (int templateIndex = 0, templateCount = templates.size(); templateIndex < templateCount; templateIndex++) {
            StructurePoolElement candidate = templates.get(templateIndex);
            if (candidate == EmptyPoolElement.INSTANCE) {
                return true;
            }

            List<Rotation> rotations = StructurePlacementShuffler.shuffledRotations(this.random);
            for (int rotationIndex = 0, rotationCount = rotations.size(); rotationIndex < rotationCount; rotationIndex++) {
                Rotation rotation = rotations.get(rotationIndex);
                List<StructureTemplate.StructureBlockInfo> candidateJigsaws =
                        ((StructurePoolElementCache) candidate).bts$getCachedJigsawBlocks(
                                this.structureTemplateManager,
                                BlockPos.ZERO,
                                rotation,
                                this.random
                        );
                BoundingBox localBounds = candidate.getBoundingBox(this.structureTemplateManager, BlockPos.ZERO, rotation);
                int projectionExpansion = 0;
                if (useExpansionHack && localBounds.getYSpan() <= 16) {
                    projectionExpansion = JigsawPlacementHotPath.computeProjectionExpansion(
                            candidateJigsaws,
                            localBounds,
                            aliasLookup,
                            this.pools,
                            this.structureTemplateManager,
                            this.ga$poolMaxSizeCache
                    );
                }

                for (int jigsawIndex = 0, jigsawCount = candidateJigsaws.size(); jigsawIndex < jigsawCount; jigsawIndex++) {
                    StructureTemplate.StructureBlockInfo candidateJigsaw = candidateJigsaws.get(jigsawIndex);
                    if (!JigsawBlock.canAttach(parentJigsaw, candidateJigsaw)) {
                        continue;
                    }

                    BlockPos candidateJigsawPos = candidateJigsaw.pos();
                    int offsetX = childAnchorPos.getX() - candidateJigsawPos.getX();
                    int offsetY = childAnchorPos.getY() - candidateJigsawPos.getY();
                    int offsetZ = childAnchorPos.getZ() - candidateJigsawPos.getZ();
                    int baseMinY = localBounds.minY() + offsetY;
                    StructureTemplatePool.Projection candidateProjection = candidate.getProjection();
                    boolean candidateRigid = candidateProjection == StructureTemplatePool.Projection.RIGID;
                    int candidateJigsawY = candidateJigsawPos.getY();
                    int junctionYOffset = parentYOffset - candidateJigsawY + frontStepY;
                    int groundY;
                    if (parentRigid && candidateRigid) {
                        groundY = parentMinY + junctionYOffset;
                    } else {
                        groundY = this.ga$getSurfaceHeight(surfaceHeight, parentJigsaw.pos(), heightAccessor, randomState) - candidateJigsawY;
                    }

                    int verticalShift = groundY - baseMinY;
                    BoundingBox placedBounds = JigsawPlacementHotPath.moveAndExpand(
                            localBounds,
                            offsetX,
                            offsetY + verticalShift,
                            offsetZ,
                            projectionExpansion
                    );
                    if (!ga$canPlace(freeShape, placedBounds)) {
                        continue;
                    }

                    ga$occupy(freeShape, placedBounds);
                    int parentGroundDelta = parentPiece.getGroundLevelDelta();
                    int candidateGroundDelta = candidateRigid
                            ? parentGroundDelta - junctionYOffset
                            : candidate.getGroundLevelDelta();
                    BlockPos placedPos = new BlockPos(offsetX, offsetY + verticalShift, offsetZ);
                    PoolElementStructurePiece placedPiece = new PoolElementStructurePiece(
                            this.structureTemplateManager,
                            candidate,
                            placedPos,
                            candidateGroundDelta,
                            rotation,
                            placedBounds,
                            liquidSettings
                    );

                    int junctionY;
                    if (parentRigid) {
                        junctionY = parentMinY + parentYOffset;
                    } else if (candidateRigid) {
                        junctionY = groundY + candidateJigsawY;
                    } else {
                        junctionY = this.ga$getSurfaceHeight(surfaceHeight, parentJigsaw.pos(), heightAccessor, randomState) + junctionYOffset / 2;
                    }

                    BlockPos parentJigsawPos = parentJigsaw.pos();
                    parentPiece.addJunction(new JigsawJunction(
                            childAnchorPos.getX(),
                            junctionY - parentYOffset + parentGroundDelta,
                            childAnchorPos.getZ(),
                            junctionYOffset,
                            candidateProjection
                    ));
                    placedPiece.addJunction(new JigsawJunction(
                            parentJigsawPos.getX(),
                            junctionY - candidateJigsawY + candidateGroundDelta,
                            parentJigsawPos.getZ(),
                            -junctionYOffset,
                            parentProjection
                    ));
                    this.pieces.add(placedPiece);
                    if (depth + 1 <= this.maxDepth) {
                        this.placing.add(MixinJigsawPlacement$PieceStateAccessor.ga$create(placedPiece, freeShape, depth + 1), placementPriority);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private static MutableObject<VoxelShape> ga$outerFreeShape(
            MutableObject<VoxelShape> freeShape,
            BoundingBox initialOccupied,
            int depth
    ) {
        if (!JigsawFreeSpaceTracker.enabled() || (depth != 0 && !(freeShape instanceof JigsawFreeSpaceTracker.State))) {
            return freeShape;
        }
        return JigsawFreeSpaceTracker.ensureOuterState(freeShape, initialOccupied);
    }

    @Unique
    private static boolean ga$canPlace(MutableObject<VoxelShape> freeShape, BoundingBox placedBounds) {
        if (freeShape instanceof JigsawFreeSpaceTracker.State state) {
            return state.canPlace(placedBounds);
        }
        return JigsawFreeSpaceTracker.canPlace(freeShape.getValue(), placedBounds);
    }

    @Unique
    private static void ga$occupy(MutableObject<VoxelShape> freeShape, BoundingBox placedBounds) {
        if (freeShape instanceof JigsawFreeSpaceTracker.State state) {
            state.occupy(placedBounds);
            return;
        }
        freeShape.setValue(JigsawFreeSpaceTracker.occupy(freeShape.getValue(), placedBounds));
    }

    @Unique
    private int ga$getSurfaceHeight(int[] surfaceHeight, BlockPos pos, LevelHeightAccessor heightAccessor, RandomState randomState) {
        int cached = surfaceHeight[0];
        if (cached != -1) {
            return cached;
        }

        int resolved = this.chunkGenerator.getFirstFreeHeight(
                pos.getX(),
                pos.getZ(),
                Heightmap.Types.WORLD_SURFACE_WG,
                heightAccessor,
                randomState
        );
        surfaceHeight[0] = resolved;
        return resolved;
    }

    @Unique
    private static Object2IntMap<ResourceKey<StructureTemplatePool>> ga$createPoolMaxSizeCache() {
        Object2IntOpenHashMap<ResourceKey<StructureTemplatePool>> cache = new Object2IntOpenHashMap<>();
        cache.defaultReturnValue(-1);
        return cache;
    }
}
