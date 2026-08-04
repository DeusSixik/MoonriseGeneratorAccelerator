package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Runtime-side attachment point for optional GPU payloads. */
public final class GpuPayloadRuntimeRegistry {
    private static final Map<CompiledDensityFunction, GpuIrPayload> PAYLOADS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<CompiledDensityFunction, Diagnostics> DIAGNOSTICS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private GpuPayloadRuntimeRegistry() {
    }

    public static void register(
            CompiledDensityFunction compiled,
            GpuEligibility.Report eligibility,
            GpuPayloadCompiler.Result result) {
        register(compiled, eligibility, result, "none");
    }

    public static void register(
            CompiledDensityFunction compiled,
            GpuEligibility.Report eligibility,
            GpuPayloadCompiler.Result result,
            String firstUnsupportedDetail) {
        if (compiled == null) {
            return;
        }
        DIAGNOSTICS.put(compiled, Diagnostics.from(eligibility, result, firstUnsupportedDetail));
        if (result != null && result.supported() && result.payload() != null) {
            PAYLOADS.put(compiled, result.payload());
        }
    }

    public static void register(CompiledDensityFunction compiled, GpuPayloadCompiler.Result result) {
        register(compiled, null, result);
    }

    public static void inherit(CompiledDensityFunction source, CompiledDensityFunction target) {
        if (source == null || target == null || source == target) {
            return;
        }
        GpuIrPayload payload = lookup(source);
        if (payload != null) {
            PAYLOADS.put(target, payload);
        }
        Diagnostics diagnostics = diagnostics(source);
        if (diagnostics != null) {
            DIAGNOSTICS.put(target, diagnostics);
        }
    }

    public static GpuIrPayload lookup(CompiledDensityFunction compiled) {
        if (compiled == null) {
            return null;
        }
        return PAYLOADS.get(compiled);
    }

    public static Diagnostics diagnostics(CompiledDensityFunction compiled) {
        if (compiled == null) {
            return null;
        }
        return DIAGNOSTICS.get(compiled);
    }

    public static void clear() {
        PAYLOADS.clear();
        DIAGNOSTICS.clear();
    }

    public record Diagnostics(
            boolean eligibilityReady,
            boolean payloadReady,
            String firstEligibilityBlocker,
            String firstUnsupportedNode,
            String firstUnsupportedDetail,
            List<String> eligibilityBlockers) {
        private static Diagnostics from(
                GpuEligibility.Report eligibility,
                GpuPayloadCompiler.Result result,
                String firstUnsupportedDetail) {
            boolean payloadReady = result != null && result.supported() && result.payload() != null;
            String firstUnsupported = result == null ? "null-result" : result.firstUnsupportedNode();
            if (firstUnsupported == null || firstUnsupported.isBlank()) {
                firstUnsupported = "none";
            }
            if (firstUnsupportedDetail == null || firstUnsupportedDetail.isBlank()) {
                firstUnsupportedDetail = firstUnsupported;
            }
            if ("none".equals(firstUnsupportedDetail) && result != null) {
                String resultDetail = result.firstUnsupportedDetail();
                if (resultDetail != null && !resultDetail.isBlank()) {
                    firstUnsupportedDetail = resultDetail;
                }
            }
            if (eligibility == null) {
                return new Diagnostics(false, payloadReady, "unknown", firstUnsupported, firstUnsupportedDetail, List.of());
            }
            return new Diagnostics(
                    eligibility.eligible(),
                    payloadReady,
                    eligibility.firstBlocker(),
                    firstUnsupported,
                    firstUnsupportedDetail,
                    eligibility.blockers().entrySet().stream()
                            .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                            .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                            .toList());
        }
    }
}
