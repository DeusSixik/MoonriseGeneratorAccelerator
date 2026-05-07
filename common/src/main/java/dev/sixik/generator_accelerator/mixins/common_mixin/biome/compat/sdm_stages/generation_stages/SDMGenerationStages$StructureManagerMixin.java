package dev.sixik.generator_accelerator.mixins.common_mixin.biome.compat.sdm_stages.generation_stages;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.darkhax.gamestages.GameStageHelper;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.sixik.sdmgenerationstages.stage.StageContainer;
import net.sixik.sdmgenerationstages.stage.type.StructureStage;
import net.sixik.sdmgenerationstages.utils.ChunkHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Consumer;

@Mixin({StructureManager.class})
public abstract class SDMGenerationStages$StructureManagerMixin {

    @Shadow @Final private LevelAccessor level;

    @Shadow public abstract void fillStartsForStructure(Structure p_220481_, LongSet p_220482_, Consumer<StructureStart> p_220483_);

    @Inject(method = "startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    public void sdm$startsForStructure(SectionPos p_220505_, Structure p_220506_, CallbackInfoReturnable<List<StructureStart>> cir){
        LongSet longset = this.level.getChunk(p_220505_.x(), p_220505_.z(), ChunkStatus.STRUCTURE_REFERENCES).getReferencesForStructure(p_220506_);
        ObjectArrayList<StructureStart> builder = new ObjectArrayList<>();
        this.fillStartsForStructure(p_220506_, longset, builder::add);
        if(!builder.isEmpty()) {
            final ResourceLocation structureId = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(p_220506_);
            for (StructureStage structure : StageContainer.INSTANCE.STRUCTURES) {
                if(structure.structures.contains(structureId)){
                    if(!ChunkHelper.isLevelHavePlayers(level)) {
                        cir.setReturnValue(new ObjectArrayList<>());
                        return;
                    }

                    Player player = ChunkHelper.getNearestPlayer(level, p_220505_.origin());
                    if(player == null || !GameStageHelper.hasStage(player, structure.stage)) {
                        cir.setReturnValue(new ObjectArrayList<>());
                        return;
                    }
                }
            }
        }
        cir.setReturnValue(builder);
    }
}
