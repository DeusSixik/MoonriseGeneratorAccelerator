package dev.sixik.generator_accelerator.common.density.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.MapAllSession;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Wrap {@link NoiseRouter#mapAll}'s visitor argument in a {@link MapAllSession} so the
 * 15 per-field {@code field.mapAll(visitor)} calls share a single per-session memo for
 * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction}
 * results.
 *
 * <h2>Why we hook here</h2>
 *
 * <p>{@code NoiseChunk}'s constructor runs {@code router.mapAll(this::wrap)} exactly once
 * per chunk. The vanilla implementation calls {@code field.mapAll(visitor)} 15 times in a
 * row, threading the same {@code Visitor} through each field. The fields share huge
 * sub-trees by identity:
 *
 * <ul>
 *   <li>{@code shift_x} / {@code shift_z} markers feed every shifted-noise field
 *       (temperature, vegetation, continents, erosion, depth, ridges).</li>
 *   <li>{@code blended_noise} feeds {@code initial_density_without_jaggedness} and
 *       {@code final_density}.</li>
 *   <li>{@code overworld/base_3d_noise} feeds half the climate router.</li>
 * </ul>
 *
 * <p>Without sharing a session, each field call enters
 * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction#mapAll}
 * with the raw {@code NoiseChunk::wrap} lambda, allocates its own
 * {@link MapAllSession}, and re-walks every shared compiled subtree from scratch — each
 * walk allocates a fresh rebound {@code CompiledDensityFunction} and runs through the
 * marker-extern logic again. Sharing the session collapses that to one walk per unique
 * compiled subtree across the entire router.
 *
 * <h2>Why {@link ModifyVariable} on the parameter</h2>
 *
 * <p>The mixin replaces the {@code visitor} parameter at HEAD; the rest of the method
 * runs unchanged and just happens to thread our session wrapper to every field's
 * {@code mapAll}. {@link MapAllSession#apply(DensityFunction)} and
 * {@link MapAllSession#visitNoise} forward to the original visitor, so observable
 * behaviour for vanilla, datapacks, and cooperating mods is identical.
 *
 * <p>If another mod also mixes into {@code NoiseRouter#mapAll}, our injection composes
 * cleanly — we modify the parameter, they see the wrapped session as their incoming
 * visitor. The wrapper itself is a {@code DensityFunction.Visitor}, so any downstream
 * code that polymorphically calls {@code visitor.apply} or {@code visitor.visitNoise}
 * still hits the user's hooks. Mixin {@code priority = 2000} keeps this ahead of
 * mods such as Moonrise (default 1000) when multiple injectors compete.
 *
 * <h2>Re-entrancy</h2>
 *
 * <p>If a caller already passed in a {@link MapAllSession} (e.g. they're nesting
 * router walks for some advanced bookkeeping), we leave it alone — the
 * {@code instanceof} check inside {@link MapAllSession} preserves the outer session
 * across re-entrant calls.
 */
@Mixin(value = NoiseRouter.class, priority = 2000)
public abstract class NoiseRouterMixin {

    @ModifyVariable(method = "mapAll", at = @At("HEAD"), argsOnly = true)
    private DensityFunction.Visitor dfc$installSession(DensityFunction.Visitor original) {
        if (original instanceof MapAllSession) {
            return original;
        }
        return new MapAllSession(original);
    }
}
