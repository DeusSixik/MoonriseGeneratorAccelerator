package dev.sixik.generator_accelerator.common.density.mixin.noise;

import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessors for the three private fields of {@link NormalNoise} we have to
 * see in order to inline its octave loops at compile-time.
 *
 * <p>Vanilla {@code NormalNoise.getValue} fans out into {@code first.getValue} and
 * {@code second.getValue}, multiplied by {@code valueFactor}. None of those fields
 * are exposed, so without a mixin accessor we'd be limited to the
 * {@code INVOKEVIRTUAL NormalNoise.getValue(DDD)D} call shape the un-specialised
 * codegen already emits.
 *
 * <p>All three accessors are pure getters with zero side effects — Mixin's
 * {@link Accessor} synthesises a method that does a single {@code GETFIELD}, which
 * HotSpot inlines aggressively. The mixin is read-only (no {@code @Final}-stripping,
 * no setter), so it's safe to apply unconditionally on every NormalNoise instance.
 *
 * <p>Field names are stable in 1.21.x Mojmap; if a future MC release renames any of
 * them, {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache}
 * will fail loudly when it tries to bind the accessor and the affected noise will fall
 * back to the legacy un-inlined emission path.
 */
@Mixin(value = NormalNoise.class, priority = 2000)
public interface NormalNoiseAccessor {

    @Accessor("valueFactor")
    double dfc$getValueFactor();

    @Accessor("first")
    PerlinNoise dfc$getFirst();

    @Accessor("second")
    PerlinNoise dfc$getSecond();
}
