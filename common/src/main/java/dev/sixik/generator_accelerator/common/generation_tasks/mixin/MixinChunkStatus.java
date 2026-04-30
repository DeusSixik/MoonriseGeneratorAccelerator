package dev.sixik.generator_accelerator.common.generation_tasks.mixin;

import dev.sixik.generator_accelerator.common.generation_tasks.GAChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;

@Mixin(ChunkStatus.class)
public abstract class MixinChunkStatus {

    @Shadow
    private static ChunkStatus register(String string, @Nullable ChunkStatus chunkStatus, EnumSet<Heightmap.Types> enumSet, ChunkType chunkType) {
        throw new NotImplementedException();
    }

    @Shadow
    @Final
    public static ChunkStatus BIOMES;

    @Shadow
    @Final
    public static EnumSet<Heightmap.Types> FINAL_HEIGHTMAPS;

    @Shadow
    @Final
    @Mutable
    public static ChunkStatus FEATURES;

    @Mutable
    @Shadow
    @Final
    public static ChunkStatus CARVERS;

    @Mutable
    @Shadow
    @Final
    public static ChunkStatus SURFACE;

    @Mutable
    @Shadow
    @Final
    public static ChunkStatus NOISE;

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/status/ChunkStatus;register(Ljava/lang/String;Lnet/minecraft/world/level/chunk/status/ChunkStatus;Ljava/util/EnumSet;Lnet/minecraft/world/level/chunk/status/ChunkType;)Lnet/minecraft/world/level/chunk/status/ChunkStatus;", ordinal = 7))
    private static ChunkStatus bts$init$redirect_features(String string, ChunkStatus chunkStatus, EnumSet<Heightmap.Types> enumSet, ChunkType chunkType) {
        return null;
    }

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/status/ChunkStatus;register(Ljava/lang/String;Lnet/minecraft/world/level/chunk/status/ChunkStatus;Ljava/util/EnumSet;Lnet/minecraft/world/level/chunk/status/ChunkType;)Lnet/minecraft/world/level/chunk/status/ChunkStatus;", ordinal = 6))
    private static ChunkStatus bts$init$redirect_carvers(String string, ChunkStatus chunkStatus, EnumSet<Heightmap.Types> enumSet, ChunkType chunkType) {
        return null;
    }

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/status/ChunkStatus;register(Ljava/lang/String;Lnet/minecraft/world/level/chunk/status/ChunkStatus;Ljava/util/EnumSet;Lnet/minecraft/world/level/chunk/status/ChunkType;)Lnet/minecraft/world/level/chunk/status/ChunkStatus;", ordinal = 5))
    private static ChunkStatus bts$init$redirect_surface(String string, ChunkStatus chunkStatus, EnumSet<Heightmap.Types> enumSet, ChunkType chunkType) {
        return null;
    }

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/status/ChunkStatus;register(Ljava/lang/String;Lnet/minecraft/world/level/chunk/status/ChunkStatus;Ljava/util/EnumSet;Lnet/minecraft/world/level/chunk/status/ChunkType;)Lnet/minecraft/world/level/chunk/status/ChunkStatus;", ordinal = 4))
    private static ChunkStatus bts$init$redirect_noise(String string, ChunkStatus chunkStatus, EnumSet<Heightmap.Types> enumSet, ChunkType chunkType) {
        return null;
    }

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void bts$init(CallbackInfo ci) {
        GAChunkStatus.TERRAIN = register("terrain", BIOMES, FINAL_HEIGHTMAPS, ChunkType.PROTOCHUNK);
        CARVERS = GAChunkStatus.TERRAIN;
        SURFACE = GAChunkStatus.TERRAIN;
        NOISE = GAChunkStatus.TERRAIN;
        FEATURES = register("features", GAChunkStatus.TERRAIN, FINAL_HEIGHTMAPS, ChunkType.PROTOCHUNK);
    }
}
