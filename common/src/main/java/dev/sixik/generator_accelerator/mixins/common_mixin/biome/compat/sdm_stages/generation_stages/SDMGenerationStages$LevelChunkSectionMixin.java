package dev.sixik.generator_accelerator.mixins.common_mixin.biome.compat.sdm_stages.generation_stages;

import com.bawnorton.mixinsquared.TargetHandler;
import net.darkhax.gamestages.GameStageHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.sixik.sdmgamestageshelper.SDMGameStagesHelper;
import net.sixik.sdmgenerationstages.stage.StageContainer;
import net.sixik.sdmgenerationstages.stage.type.BiomeStage;
import net.sixik.sdmgenerationstages.utils.ChunkHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LevelChunkSection.class, priority = 1500, remap = false)
public class SDMGenerationStages$LevelChunkSectionMixin {


    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.mixins.common_mixin.biome.MixinLevelChunkSection$optimize_biome_iteration",
            name = "rms$shouldCancelBiome"
    )
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), cancellable = true)
    private void rms$sdmsCheck(Holder<Biome> biome, CallbackInfoReturnable<Boolean> cir) {
        if (StageContainer.INSTANCE.BIOMES.isEmpty()) {
            return;
        }

        if (ChunkHelper.registryAccess == null) {
            cir.setReturnValue(true);
            return;
        }

        final RegistryAccess access = ChunkHelper.registryAccess;
        final ResourceLocation biomeKey = access.registryOrThrow(Registries.BIOME).getKey(biome.value());

        for (BiomeStage stage : StageContainer.INSTANCE.BIOMES) {
            if (stage.biomes.contains(biomeKey)
                    && (ChunkHelper.player == null || !GameStageHelper.hasStage(ChunkHelper.player, stage.stage))) {
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
