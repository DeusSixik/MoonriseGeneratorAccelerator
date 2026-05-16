package dev.sixik.generator_accelerator.common.worldgen.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAWorldgenDiagnosticsFeedbackTest {
    @Test
    void feedbackPromotesUnsafeFallbacksIntoCompatTargets() {
        Map<String, Object> feedback = GAWorldgenDiagnosticsFeedback.snapshot(
                profileMetrics(),
                map("countsByFallbackReason", map("effect scan unsafe: io constant", 2L)),
                map("governor", map("worldgenPressure", 3L, "worldgenPressureTarget", 2L, "compileActiveLimit", 1L)),
                map(
                        "inFlight", 1L,
                        "maxInFlightSeen", 4L,
                        "pooledEstimatedRetainedBytes", 8192L,
                        "rejected", 2L,
                        "metrics", map(
                                "importNanos", 10L,
                                "computeNanos", 20L,
                                "finalizeNanos", 30L,
                                "repackNanos", 40L,
                                "terrainFailures", 1L
                        )
                ),
                map("collisions", 1L, "failures", 0L),
                map("runtimeGates", map("patternOptimizerRuntime", true, "serialUnsafeLaneRuntime", false))
        );

        assertEquals("ga-worldgen-feedback-v1", feedback.get("schema"));
        assertFalse(list(feedback, "quarantineList").isEmpty());
        assertFalse(list(feedback, "suggestedCompatTargets").isEmpty());
        assertEquals(List.of("serialUnsafeLaneRuntime"), feedback.get("pipelineMissingRuntimeGates"));

        Map<?, ?> workspace = (Map<?, ?>) feedback.get("workspaceMemoryTimeBreakdown");
        assertEquals(8192L, workspace.get("pooledEstimatedRetainedBytes"));
        assertEquals(1L, workspace.get("terrainFailures"));

        Map<?, ?> commit = (Map<?, ?>) feedback.get("commitBacklogHints");
        assertEquals(true, commit.get("needsAttention"));

        Map<?, ?> scheduler = (Map<?, ?>) feedback.get("schedulerPressureHints");
        assertEquals(true, scheduler.get("compileThrottled"));
    }

    private static Map<String, Object> profileMetrics() {
        return map(
                "estimatedCostTotal", 77L,
                "tiers", map("SERIAL_ISOLATED", 5L, "VANILLA_FALLBACK_DISABLED", 1L),
                "namespaces", map("examplemod", 5L, "minecraft", 2L),
                "classes", map("example.Feature", 5L, "minecraft.Feature", 2L),
                "fallbackReasons", map(
                        "effect scan unsafe: io constant", 3L,
                        "unknown namespace defaults to serial safe vanilla", 2L
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> source, String key) {
        return (List<Map<String, Object>>) source.get(key);
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            out.put((String) entries[i], entries[i + 1]);
        }
        return out;
    }
}
