package dev.sixik.generator_accelerator.common.blender.mixin;

import dev.sixik.generator_accelerator.common.blender.NewBlender;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Blender.class)
public class MixinBlender$redirect_to_new_blender {


    /**
     * @author Sixik
     * @reason Redirect to new Blender
     */
    @Overwrite
    public static Blender of(@Nullable WorldGenRegion worldGenRegion) {
        return NewBlender.ofNew(worldGenRegion);
    }

    /**
     * @author Sixik
     * @reason Redirect to new Blender
     */
    @Overwrite
    public static Blender empty() {
        return NewBlender.emptyNew();
    }
}
