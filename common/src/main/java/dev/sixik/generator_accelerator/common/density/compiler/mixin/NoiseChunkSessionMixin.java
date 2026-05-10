package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.MapAllSession;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Per-{@link NoiseChunk} {@link MapAllSession} sharing across every
 * {@code DensityFunction.mapAll(this::wrap)} call inside the chunk's lifecycle.
 *
 * <h2>Why we hook here (in addition to {@link NoiseRouterMixin})</h2>
 *
 * <p>{@link NoiseRouterMixin} already wraps the visitor once per
 * {@code NoiseRouter.mapAll(visitor)} invocation, sharing one session across the
 * 15 router fields. But {@code NoiseChunk} makes <strong>two more</strong> kinds
 * of {@code mapAll} calls that are <em>not</em> on {@code NoiseRouter}:
 *
 * <ol>
 *   <li>{@code NoiseChunk.<init>} calls
 *       {@code DensityFunctions.cacheAllInCell(add(noiserouter1.finalDensity(), Beardifier))
 *       .mapAll(this::wrap)} — a {@code DensityFunction.mapAll}, not a
 *       {@code NoiseRouter.mapAll}. The inner subtree shares many compiled DFs
 *       with {@code finalDensity} (literally re-uses {@code noiserouter1.finalDensity()}
 *       which is already a compiled DF after Phase 1). Without sharing the session
 *       installed by {@link NoiseRouterMixin}, this second walk allocates fresh
 *       wrappers around every shared sub-tree all over again.</li>
 *
 *   <li>{@code NoiseChunk.cachedClimateSampler} makes 6 sequential
 *       {@code field.mapAll(this::wrap)} calls (temperature, vegetation, continents,
 *       erosion, depth, ridges) — all six fields are routinely the same shifted-noise
 *       trees built on top of {@code shift_x}/{@code shift_z}. Each gets its own
 *       fresh visitor lambda from {@code this::wrap}, so without sharing they walk
 *       the same compiled subtree six times.</li>
 * </ol>
 *
 * <h2>Session lifecycles</h2>
 *
 * <p>We use <strong>two</strong> per-instance sessions because the two contexts
 * have different sharing scopes:
 *
 * <ul>
 *   <li>{@code dfc$ctorSession} — installed lazily by the constructor's first
 *       {@code mapAll} redirect and reused for the duration of {@code <init>}.
 *       It is intentionally <em>not</em> cleared at the end of {@code <init>} so
 *       that any post-construction internal {@code mapAll} call (vanilla doesn't
 *       have one but a mod might) can still benefit. The session weakly references
 *       the original visitor only for the lifetime of the chunk.</li>
 *
 *   <li>{@code dfc$samplerSession} — scoped to a single
 *       {@code cachedClimateSampler} invocation. Cleared at HEAD and RETURN so
 *       repeated calls don't accidentally cache stale wrap results from a prior
 *       sampler build (which would be a correctness bug — the user visitor could
 *       be different between calls in principle).</li>
 * </ul>
 *
 * <h2>Re-entrancy</h2>
 *
 * <p>If the incoming visitor is already a {@link MapAllSession} (e.g. a mod has
 * pre-wrapped, or the redirect has already fired earlier in this method), we
 * unwrap and reuse it instead of double-wrapping. The {@code MapAllSession}
 * compiled-memo only short-circuits on identity-equal {@link
 * dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction}
 * keys, so passing the same session through twice is harmless.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Vanilla keeps {@code NoiseChunk} wrapping single-threaded, but some modded
 * pipelines parallelize router field mapping. The session fields remain per chunk;
 * {@link #wrap(DensityFunction)} is synchronized so chunk-local cache wrappers cannot
 * be published through a racing wrapper map.
 */
@Mixin(value = NoiseChunk.class, priority = 2000)
public abstract class NoiseChunkSessionMixin {

    @Shadow
    @Final
    private Map<DensityFunction, DensityFunction> wrapped;

    @Shadow
    private DensityFunction wrapNew(DensityFunction densityFunction) {
        throw new AssertionError();
    }

    @Unique
    private MapAllSession dfc$ctorSession;

    @Unique
    private MapAllSession dfc$samplerSession;

    /**
     * @author Sixik
     * @reason Keep NoiseChunk's wrapper map safe if router field mapping is parallelized.
     */
    @Overwrite
    protected synchronized DensityFunction wrap(DensityFunction densityFunction) {
        DensityFunction cached = this.wrapped.get(densityFunction);
        if (cached != null) {
            return cached;
        }

        DensityFunction result = this.wrapNew(densityFunction);
        this.wrapped.put(densityFunction, result);
        return result;
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;")
    )
    private DensityFunction dfc$ctorMapAll(DensityFunction self, DensityFunction.Visitor visitor) {
        MapAllSession session = this.dfc$ctorSession;
        if (session == null) {
            session = (visitor instanceof MapAllSession existing) ? existing : new MapAllSession(visitor);
            this.dfc$ctorSession = session;
        }
        return self.mapAll(session);
    }

    @Inject(method = "cachedClimateSampler", at = @At("HEAD"))
    private void dfc$enterSampler(NoiseRouter router, List<Climate.ParameterPoint> points,
                                  CallbackInfoReturnable<Climate.Sampler> cir) {
        this.dfc$samplerSession = null;
    }

    @Redirect(
            method = "cachedClimateSampler",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;")
    )
    private DensityFunction dfc$samplerMapAll(DensityFunction self, DensityFunction.Visitor visitor) {
        MapAllSession session = this.dfc$samplerSession;
        if (session == null) {
            session = (visitor instanceof MapAllSession existing) ? existing : new MapAllSession(visitor);
            this.dfc$samplerSession = session;
        }
        return self.mapAll(session);
    }

    @Inject(method = "cachedClimateSampler", at = @At("RETURN"))
    private void dfc$exitSampler(NoiseRouter router, List<Climate.ParameterPoint> points,
                                 CallbackInfoReturnable<Climate.Sampler> cir) {
        this.dfc$samplerSession = null;
    }
}
