package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public class MixinNoiseChunk$Cache2D implements DfcCellCacheAccess.VersionedCache {

    @Shadow
    private long lastPos2D;
    @Shadow private double lastValue;
    @Shadow @Final
    private DensityFunction function;

    @Unique
    private volatile long dfc$cacheFastPathVersion;

    @Unique
    private volatile boolean dfc$cacheFastPathValid;

    /**
     * @author Sixik
     * @reason Inline ChunkPos.asLong
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext ctx) {
        final int x = ctx.blockX();
        final int z = ctx.blockZ();

        final long key = (long)x & 0xFFFFFFFFL | ((long)z << 32);

        if (this.lastPos2D == key) {
            return this.lastValue;
        } else {
            long writeVersion = this.dfc$cacheFastPathVersion + 1L;
            if ((writeVersion & 1L) == 0L) {
                writeVersion++;
            }
            this.dfc$cacheFastPathVersion = writeVersion;
            this.dfc$cacheFastPathValid = false;
            this.lastPos2D = key;
            final double val;
            try {
                val = this.function.compute(ctx);
            } catch (ArrayIndexOutOfBoundsException e) {
                String compiledState = this.function instanceof CompiledDensityFunction compiled
                        ? ", " + compiled.dfc$debugState()
                        : "";
                ArrayIndexOutOfBoundsException enriched = new ArrayIndexOutOfBoundsException(
                        e.getMessage() + " while computing NoiseChunk.Cache2D function="
                                + this.function.getClass().getName()
                                + " at x=" + x + ", z=" + z
                                + compiledState);
                enriched.initCause(e);
                throw enriched;
            } finally {
                this.dfc$cacheFastPathVersion = writeVersion + 1L;
            }
            this.lastValue = val;
            this.dfc$cacheFastPathValid = true;
            return val;
        }
    }

    @Override
    public long dfc$cacheFastPathVersion() {
        return this.dfc$cacheFastPathVersion;
    }

    @Override
    public boolean dfc$cacheFastPathValid() {
        return this.dfc$cacheFastPathValid;
    }

}
