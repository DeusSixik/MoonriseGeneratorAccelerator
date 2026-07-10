package dev.sixik.generator_accelerator.common.surface_compiler.frontend;

import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterRegistry;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterSafetyClass;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.CertifiedVectorSurfaceAdapter;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.KnownUnsafeRuleRegistry;
import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.FallbackReason;

public final class SurfaceRuleClassifier {
    private final AdapterRegistry adapters;
    private final KnownUnsafeRuleRegistry unsafeRules;

    public SurfaceRuleClassifier() {
        this(new AdapterRegistry(), new KnownUnsafeRuleRegistry());
    }

    public SurfaceRuleClassifier(AdapterRegistry adapters, KnownUnsafeRuleRegistry unsafeRules) {
        this.adapters = adapters;
        this.unsafeRules = unsafeRules;
    }

    public Classification classify(SurfaceRuleScanner.SurfaceScanResult scan) {
        if (this.unsafeRules.contains(scan.rootClassName())) {
            return new Classification(false, false, FallbackReason.UNSAFE_RULE);
        }
        if (!scan.vanillaOwned()) {
            return classifyExternal(scan.rootClassName());
        }
        if (scan.containsOpaqueCallouts()) {
            return new Classification(false, false, FallbackReason.UNSAFE_RULE);
        }
        return new Classification(true, false, FallbackReason.UNCERTIFIED);
    }

    private Classification classifyExternal(String rootClassName) {
        return this.adapters.find(rootClassName)
                .map(adapter -> {
                    AdapterSafetyClass safety = adapter.descriptor().safetyClass();
                    boolean primitive = adapter.descriptor().primitiveAbi();
                    boolean vector = safety == AdapterSafetyClass.READ_ONLY_CERTIFIED_VECTOR
                            && primitive
                            && adapter instanceof CertifiedVectorSurfaceAdapter
                            && adapter.descriptor().vectorAbi()
                            && adapter.descriptor().vectorWidth() > 0
                            && adapter.descriptor().vectorWidth() == ((CertifiedVectorSurfaceAdapter) adapter).vectorWidth();
                    boolean hybrid = switch (safety) {
                        case EXACT_INLINE,
                             EXACT_ORDERED_INLINE,
                             READ_ONLY_COMPILER_ITERATED_SCALAR,
                             HALO_READ_ONLY,
                             CONTEXT_SENSITIVE -> primitive;
                        case READ_ONLY_CERTIFIED_VECTOR -> vector;
                        case READ_ONLY_LEGACY_BLOCKPOS,
                             ORDERED_OPAQUE,
                             MUTATING_OR_UNKNOWN,
                             UNSAFE -> false;
                    };
                    return new Classification(hybrid, hybrid, vector, hybrid ? FallbackReason.UNCERTIFIED : FallbackReason.UNSAFE_RULE);
                })
                .orElseGet(() -> new Classification(false, false, FallbackReason.UNSAFE_RULE));
    }

    public record Classification(boolean compilerEligible, boolean hybridEligible, boolean vectorEligible, FallbackReason fallbackReason) {
        public Classification(boolean compilerEligible, boolean hybridEligible, FallbackReason fallbackReason) {
            this(compilerEligible, hybridEligible, false, fallbackReason);
        }
    }
}
