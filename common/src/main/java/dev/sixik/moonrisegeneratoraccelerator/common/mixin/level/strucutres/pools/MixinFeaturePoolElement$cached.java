package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.strucutres.pools;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(FeaturePoolElement.class)
public abstract class MixinFeaturePoolElement$cached extends StructurePoolElement {

    @Shadow
    @Final
    private CompoundTag defaultJigsawNBT;

    private StructureTemplate.StructureBlockInfo cachedInfo;

    protected MixinFeaturePoolElement$cached(StructureTemplatePool.Projection projection) {
        super(projection);
    }

    private StructureTemplate.StructureBlockInfo getInfo() {
        if (this.cachedInfo == null) {
            this.cachedInfo = new StructureTemplate.StructureBlockInfo(
                    BlockPos.ZERO,
                    Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION, FrontAndTop.fromFrontAndTop(Direction.DOWN, Direction.SOUTH)),
                    this.defaultJigsawNBT
            );
        }
        return this.cachedInfo;
    }

    @Inject(method = "getShuffledJigsawBlocks", at = @At("HEAD"), cancellable = true)
    public void bts$getShuffledJigsawBlocks(
           final StructureTemplateManager structureTemplateManager,
           final BlockPos blockPos,
           final Rotation rotation,
           final RandomSource randomSource,
           final CallbackInfoReturnable<List<StructureTemplate.StructureBlockInfo>> cir
    ) {
        final List<StructureTemplate.StructureBlockInfo> list = new ObjectArrayList<>(1);

        StructureTemplate.StructureBlockInfo base = getInfo();

        if (blockPos.equals(BlockPos.ZERO)) {
            list.add(base);
        } else {
            list.add(new StructureTemplate.StructureBlockInfo(blockPos.immutable(), base.state(), base.nbt()));
        }
        cir.setReturnValue(list);
    }
}
