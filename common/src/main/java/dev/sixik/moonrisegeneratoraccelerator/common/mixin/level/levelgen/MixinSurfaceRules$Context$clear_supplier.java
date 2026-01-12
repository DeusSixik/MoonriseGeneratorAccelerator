package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.SurfaceRulesContextBiomeGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;
import java.util.function.Supplier;

@Mixin(SurfaceRules.Context.class)
public abstract class MixinSurfaceRules$Context$clear_supplier implements SurfaceRulesContextBiomeGetter {


    @Unique
    private int[] biomePossition = new int[3];

    @Unique
    private Holder<Biome> biomeHolderCache; // null = не вычислено для текущих координат

    @Unique
    private Biome biomeCache;

    @Shadow
    @Final
    private Function<BlockPos, Holder<Biome>> biomeGetter;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos pos;

    @Shadow
    private Supplier<Holder<Biome>> biome;

    @Shadow
    private long lastUpdateY;

    @Shadow
    private int blockY;

    @Shadow
    private int waterHeight;

    @Shadow
    private int stoneDepthBelow;

    @Shadow
    private int stoneDepthAbove;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(SurfaceSystem surfaceSystem, RandomState randomState, ChunkAccess chunkAccess, NoiseChunk noiseChunk, Function function, Registry registry, WorldGenerationContext worldGenerationContext, CallbackInfo ci) {
        this.biome = this::bts$getBiomeHolderCached;
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public void updateY(int i, int j, int k, int l, int m, int n) {
        ++this.lastUpdateY;

        this.biomeHolderCache = null;
        this.biomeCache = null;

        final int[] bPos = biomePossition;
        bPos[0] = l;
        bPos[1] = m;
        bPos[2] = n;

        this.blockY = m;
        this.waterHeight = k;
        this.stoneDepthBelow = j;
        this.stoneDepthAbove = i;
    }

    @Override
    public Supplier<Holder<Biome>> bts$getBiomeSupplier() {
        return this::bts$getBiomeHolderCached;
    }

    @Override
    public Holder<Biome> bts$getBiomeHolderCached() {
        Holder<Biome> b = biomeHolderCache;
        if (b == null) {
            final int[] bPos = biomePossition;
            b = biomeGetter.apply(pos.set(bPos[0], bPos[1], bPos[2]));
            biomeHolderCache = b;
        }
        return b;
    }

    @Override
    public Biome bts$getBiomeCached() {
        Biome b = biomeCache;
        if(b == null) {
            final int[] bPos = biomePossition;
            b = biomeGetter.apply(pos.set(bPos[0], bPos[1], bPos[2])).value();
            biomeCache = b;
        }
        return b;
    }

    @Override
    public int[] bts$getPositions() {
        return biomePossition;
    }
}
