package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.presets;

import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldPresets.class)
public class MixinWorldPresets$add_noise_optimizer {

//    @Shadow
//    @Final
//    public static ResourceKey<WorldPreset> NORMAL;
//
//    @Inject(method = "createNormalWorldDimensions", at = @At("HEAD"))
//    private static void bts$createNormalWorldDimensions(RegistryAccess registryAccess, CallbackInfoReturnable<WorldDimensions> cir) {
//        registryAccess.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(NORMAL).value().createWorldDimensions();
//    }
}
