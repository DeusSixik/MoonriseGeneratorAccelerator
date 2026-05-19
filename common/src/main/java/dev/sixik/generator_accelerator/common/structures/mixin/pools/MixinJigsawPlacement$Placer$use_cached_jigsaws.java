package dev.sixik.generator_accelerator.common.structures.mixin.pools;

import dev.sixik.generator_accelerator.common.structures.StructurePlacementShuffler;
import dev.sixik.generator_accelerator.common.structures.StructurePoolElementCache;
import dev.sixik.generator_accelerator.common.structures.StructureTemplatePoolCache;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public class MixinJigsawPlacement$Placer$use_cached_jigsaws {

    @Redirect(method = "tryPlacingChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/pools/StructurePoolElement;getShuffledJigsawBlocks(Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    List<StructureTemplate.StructureBlockInfo> bts$tryPlacingChildren(StructurePoolElement instance, StructureTemplateManager structureTemplateManager, BlockPos pos, Rotation rotation, RandomSource randomSource) {
        return ((StructurePoolElementCache) instance).bts$getCachedJigsawBlocks(structureTemplateManager, pos, rotation, randomSource);
    }

    @Redirect(method = "tryPlacingChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool;getShuffledTemplates(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    List<StructurePoolElement> bts$getShuffledTemplates(StructureTemplatePool instance, RandomSource randomSource) {
        return ((StructureTemplatePoolCache) instance).bts$getCachedShuffledTemplates(randomSource);
    }

    @Redirect(method = "tryPlacingChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Rotation;getShuffled(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    List<Rotation> bts$getShuffledRotations(RandomSource randomSource) {
        return StructurePlacementShuffler.shuffledRotations(randomSource);
    }
}
