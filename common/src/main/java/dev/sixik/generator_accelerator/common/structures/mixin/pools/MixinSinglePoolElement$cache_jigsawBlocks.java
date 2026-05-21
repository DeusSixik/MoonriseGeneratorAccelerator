package dev.sixik.generator_accelerator.common.structures.mixin.pools;

import dev.sixik.generator_accelerator.common.structures.StructureJigsawConnectorPlan;
import dev.sixik.generator_accelerator.common.structures.StructurePoolElementCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(value = SinglePoolElement.class, priority = 997)
public abstract class MixinSinglePoolElement$cache_jigsawBlocks extends StructurePoolElement implements StructurePoolElementCache {

    @Unique
    private final AtomicReferenceArray<StructureJigsawConnectorPlan> bts$jigsawPlans = new AtomicReferenceArray<>(4);
    @Unique
    private final AtomicReferenceArray<Vec3i> bts$cachedSizes = new AtomicReferenceArray<>(4);
    @Unique
    private final AtomicReferenceArray<BoundingBox> bts$cachedLocalBoxes = new AtomicReferenceArray<>(4);

    @Shadow
    protected abstract StructureTemplate getTemplate(StructureTemplateManager structureTemplateManager);

    protected MixinSinglePoolElement$cache_jigsawBlocks(StructureTemplatePool.Projection projection) {
        super(projection);
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> bts$getCachedJigsawBlocks(
            StructureTemplateManager manager,
            BlockPos pos,
            Rotation rotation,
            RandomSource random
    ) {
        Rotation safeRotation = bts$safeRotation(rotation);
        int index = safeRotation.ordinal();
        StructureJigsawConnectorPlan plan = this.bts$jigsawPlans.get(index);
        if (plan == null) {
            StructureTemplate template = this.getTemplate(manager);
            ObjectArrayList<StructureTemplate.StructureBlockInfo> blocks = template.filterBlocks(
                    BlockPos.ZERO,
                    new StructurePlaceSettings().setRotation(safeRotation),
                    Blocks.JIGSAW,
                    true
            );
            StructureJigsawConnectorPlan compiled = StructureJigsawConnectorPlan.compile(blocks);
            if (this.bts$jigsawPlans.compareAndSet(index, null, compiled)) {
                plan = compiled;
            } else {
                plan = this.bts$jigsawPlans.get(index);
            }
        }

        return plan.shuffled(pos, random);
    }

    /**
     * @author Sixik
     * @reason SinglePoolElement size is immutable for a template/rotation; vanilla re-derives it for every candidate.
     */
    @Overwrite
    public Vec3i getSize(StructureTemplateManager manager, Rotation rotation) {
        Rotation safeRotation = bts$safeRotation(rotation);
        int index = safeRotation.ordinal();
        Vec3i size = this.bts$cachedSizes.get(index);
        if (size != null) {
            return size;
        }

        Vec3i computed = this.getTemplate(manager).getSize(safeRotation);
        if (this.bts$cachedSizes.compareAndSet(index, null, computed)) {
            return computed;
        }
        return this.bts$cachedSizes.get(index);
    }

    /**
     * @author Sixik
     * @reason Cache the local rotated bounds and only copy/move it per call; callers may mutate returned boxes.
     */
    @Overwrite
    public BoundingBox getBoundingBox(StructureTemplateManager manager, BlockPos pos, Rotation rotation) {
        Rotation safeRotation = bts$safeRotation(rotation);
        int index = safeRotation.ordinal();
        BoundingBox localBox = this.bts$cachedLocalBoxes.get(index);
        if (localBox == null) {
            BoundingBox computed = this.getTemplate(manager).getBoundingBox(
                    new StructurePlaceSettings().setRotation(safeRotation),
                    BlockPos.ZERO
            );
            if (this.bts$cachedLocalBoxes.compareAndSet(index, null, computed)) {
                localBox = computed;
            } else {
                localBox = this.bts$cachedLocalBoxes.get(index);
            }
        }
        return bts$copyMoved(localBox, pos);
    }

    @Unique
    private static Rotation bts$safeRotation(Rotation rotation) {
        return rotation == null ? Rotation.NONE : rotation;
    }

    @Unique
    private static BoundingBox bts$copyMoved(BoundingBox box, BlockPos pos) {
        int dx = pos.getX();
        int dy = pos.getY();
        int dz = pos.getZ();
        return new BoundingBox(
                box.minX() + dx,
                box.minY() + dy,
                box.minZ() + dz,
                box.maxX() + dx,
                box.maxY() + dy,
                box.maxZ() + dz
        );
    }
}
