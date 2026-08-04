package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$PalettedContainerExtern;
import dev.sixik.generator_accelerator.api.patches.GA$PaletteDataExtern;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PalettedContainer.class)
public class MixinPalettedContainer<T> implements GA$PalettedContainerExtern {


    @Shadow
    @Final
    public PalettedContainer.Strategy strategy;

    @Shadow
    public volatile PalettedContainer.Data<T> data;

    @Override
    public int ga$getRawData(int x, int y, int z) {
        PalettedContainer.Data<T> currentData = this.data;
        int index = strategy.getIndex(x, y, z);
        if ((Object) currentData instanceof GA$PaletteDataExtern<?> extern) {
            int[] rawStorage = extern.bts$getRawStorage();
            if (rawStorage != null) {
                return rawStorage[index];
            }
        }
        return currentData.storage().get(index);
    }
}
