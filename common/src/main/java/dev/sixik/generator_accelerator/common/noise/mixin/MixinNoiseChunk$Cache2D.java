package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.DfcCompiledMathFallback;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public class MixinNoiseChunk$Cache2D {

    @Shadow
    private long lastPos2D;
    @Shadow private double lastValue;
    @Shadow @Final
    private DensityFunction function;
    @Unique
    private volatile DensityFunction ga$compiledMathFallback;
    @Unique
    private volatile boolean ga$compiledMathFallbackChecked;

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
            this.lastPos2D = key;
            final double val;
            try {
                DensityFunction compiled = this.ga$compiledMathFallback();
                val = (compiled != null ? compiled : this.function).compute(ctx);
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
            }
            this.lastValue = val;
            return val;
        }
    }

    @Unique
    private DensityFunction ga$compiledMathFallback() {
        if (!this.ga$compiledMathFallbackChecked) {
            this.ga$compiledMathFallback = DfcCompiledMathFallback.compileIfFullyMath(this.function);
            this.ga$compiledMathFallbackChecked = true;
        }
        return this.ga$compiledMathFallback;
    }
}
