package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.strucutres.pools;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

@Mixin(EmptyPoolElement.class)
public class MixinEmptyPoolElement$default_empty_list {

    @Unique
    private static final List<StructureTemplate.StructureBlockInfo> moonrise_generator_accelerator$EmptyCollection = Collections.emptyList();

    @Inject(method = "getShuffledJigsawBlocks", at = @At("HEAD"), cancellable = true)
    public void bts$getShuffledJigsawBlocks(StructureTemplateManager structureTemplateManager, BlockPos blockPos, Rotation rotation, RandomSource randomSource, CallbackInfoReturnable<List<StructureTemplate.StructureBlockInfo>> cir) {
        cir.setReturnValue(moonrise_generator_accelerator$EmptyCollection);
    }
}
