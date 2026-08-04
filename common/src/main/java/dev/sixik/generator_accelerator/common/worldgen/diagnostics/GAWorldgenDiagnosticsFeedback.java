package dev.sixik.generator_accelerator.common.worldgen.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds compact Phase 11 feedback from existing low-overhead worldgen metrics.
 */
public final class GAWorldgenDiagnosticsFeedback {
    private static final int TOP_LIMIT = 12;

    private GAWorldgenDiagnosticsFeedback() {
    }

    public static Map<String, Object> snapshot(
            Map<String, Object> profileMetrics,
            Map<String, Object> registryScan,
            Map<String, Object> scheduler,
            Map<String, Object> workspace,
            Map<String, Object> commitEngine,
            Map<String, Object> pipelineStatus
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "ga-worldgen-feedback-v1");
        out.put("quarantineList", quarantineList(profileMetrics));
        out.put("topSlowUnsafeUnits", topSlowUnsafeUnits(profileMetrics));
        out.put("tierTimePerNamespace", tierTimePerNamespace(profileMetrics));
        out.put("mixinConflictReport", mixinConflictReport());
        out.put("suggestedCompatTargets", suggestedCompatTargets(profileMetrics, registryScan));
        out.put("workspaceMemoryTimeBreakdown", workspaceBreakdown(workspace));
        out.put("commitBacklogHints", commitHints(commitEngine));
        out.put("schedulerPressureHints", schedulerHints(scheduler));
        out.put("pipelineMissingRuntimeGates", missingRuntimeGates(pipelineStatus));
        return out;
    }

    private static List<Map<String, Object>> quarantineList(Map<String, Object> profileMetrics) {
        Map<String, Object> fallbackReasons = map(profileMetrics, "fallbackReasons");
        List<Map<String, Object>> out = new ArrayList<>();
        fallbackReasons.entrySet().stream()
                .filter(entry -> isQuarantineReason(entry.getKey()))
                .sorted(countComparator())
                .limit(TOP_LIMIT)
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("reason", entry.getKey());
                    item.put("count", number(entry.getValue()));
                    item.put("action", actionForReason(entry.getKey()));
                    out.add(item);
                });
        return out;
    }

    private static List<Map<String, Object>> topSlowUnsafeUnits(Map<String, Object> profileMetrics) {
        Map<String, Object> classes = map(profileMetrics, "classes");
        long serial = number(map(profileMetrics, "tiers").get("SERIAL_ISOLATED"));
        long disabled = number(map(profileMetrics, "tiers").get("VANILLA_FALLBACK_DISABLED"));
        boolean unsafeDominates = serial + disabled > 0L;

        List<Map<String, Object>> out = new ArrayList<>();
        classes.entrySet().stream()
                .sorted(countComparator())
                .limit(TOP_LIMIT)
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("className", entry.getKey());
                    item.put("samples", number(entry.getValue()));
                    item.put("unsafeSerialPressure", unsafeDominates);
                    item.put("suggestion", unsafeDominates
                            ? "add manual compat, sandbox guard, or generated pattern"
                            : "monitor before compat work");
                    out.add(item);
                });
        return out;
    }

    private static List<Map<String, Object>> tierTimePerNamespace(Map<String, Object> profileMetrics) {
        Map<String, Object> namespaces = map(profileMetrics, "namespaces");
        long totalCost = number(profileMetrics == null ? null : profileMetrics.get("estimatedCostTotal"));
        List<Map<String, Object>> out = new ArrayList<>();
        namespaces.entrySet().stream()
                .sorted(countComparator())
                .limit(TOP_LIMIT)
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("namespace", entry.getKey());
                    item.put("classifiedUnits", number(entry.getValue()));
                    item.put("estimatedCostTotal", totalCost);
                    item.put("note", "cost is aggregate until per-namespace timing is enabled");
                    out.add(item);
                });
        return out;
    }

    private static Map<String, Object> mixinConflictReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detected", false);
        out.put("auditEnabled", Boolean.getBoolean("mixin.audit"));
        out.put("debugExport", Boolean.getBoolean("mixin.debug.export"));
        out.put("note", "no runtime mixin conflict feed available; use loader log with this diagnostics bundle");
        return out;
    }

    private static List<Map<String, Object>> suggestedCompatTargets(Map<String, Object> profileMetrics, Map<String, Object> registryScan) {
        Map<String, Object> namespaces = map(profileMetrics, "namespaces");
        Map<String, Object> fallbackReasons = map(profileMetrics, "fallbackReasons");
        Map<String, Object> scanFallback = map(registryScan, "countsByFallbackReason");
        List<Map<String, Object>> out = new ArrayList<>();
        namespaces.entrySet().stream()
                .sorted(countComparator())
                .limit(TOP_LIMIT / 2)
                .forEach(entry -> out.add(target("namespace", entry.getKey(), number(entry.getValue()),
                        "profile top namespace; inspect unsafe units")));
        fallbackReasons.entrySet().stream()
                .sorted(countComparator())
                .limit(TOP_LIMIT / 2)
                .forEach(entry -> out.add(target("fallbackReason", entry.getKey(), number(entry.getValue()),
                        actionForReason(entry.getKey()))));
        scanFallback.entrySet().stream()
                .sorted(countComparator())
                .limit(4)
                .forEach(entry -> out.add(target("registryFallback", entry.getKey(), number(entry.getValue()),
                        "fix registry-wide fallback source first")));
        return out.stream()
                .sorted(Comparator
                        .<Map<String, Object>>comparingLong(item -> number(item.get("score")))
                        .reversed())
                .limit(TOP_LIMIT)
                .toList();
    }

    private static Map<String, Object> workspaceBreakdown(Map<String, Object> workspace) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> metrics = map(workspace, "metrics");
        out.put("inFlight", number(workspace == null ? null : workspace.get("inFlight")));
        out.put("maxInFlightSeen", number(workspace == null ? null : workspace.get("maxInFlightSeen")));
        out.put("pooledEstimatedRetainedBytes", number(workspace == null ? null : workspace.get("pooledEstimatedRetainedBytes")));
        out.put("acquireRejected", number(workspace == null ? null : workspace.get("rejected")));
        out.put("importNanos", number(metrics.get("importNanos")));
        out.put("computeNanos", number(metrics.get("computeNanos")));
        out.put("finalizeNanos", number(metrics.get("finalizeNanos")));
        out.put("repackNanos", number(metrics.get("repackNanos")));
        out.put("terrainFailures", number(metrics.get("terrainFailures")));
        return out;
    }

    private static Map<String, Object> commitHints(Map<String, Object> commitEngine) {
        Map<String, Object> out = new LinkedHashMap<>();
        long collisions = number(commitEngine == null ? null : commitEngine.get("collisions"));
        long failures = number(commitEngine == null ? null : commitEngine.get("failures"));
        out.put("collisions", collisions);
        out.put("failures", failures);
        out.put("needsAttention", collisions > 0L || failures > 0L);
        return out;
    }

    private static Map<String, Object> schedulerHints(Map<String, Object> scheduler) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> governor = map(scheduler, "governor");
        out.put("worldgenPressure", number(governor.get("worldgenPressure")));
        out.put("worldgenPressureTarget", number(governor.get("worldgenPressureTarget")));
        out.put("compileActiveLimit", number(governor.get("compileActiveLimit")));
        out.put("compileThrottled", number(governor.get("compileActiveLimit")) <= 1L);
        return out;
    }

    private static List<String> missingRuntimeGates(Map<String, Object> pipelineStatus) {
        Map<String, Object> gates = map(pipelineStatus, "runtimeGates");
        return gates.entrySet().stream()
                .filter(entry -> Boolean.FALSE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .filter(key -> !"workspaceOnlyWritesRuntimeDisabled".equals(key))
                .sorted()
                .toList();
    }

    private static Map<String, Object> target(String kind, String id, long score, String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("id", id);
        out.put("score", score);
        out.put("action", action);
        return out;
    }

    private static boolean isQuarantineReason(String reason) {
        String lower = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return lower.contains("quarantine")
                || lower.contains("unsafe")
                || lower.contains("unsupported")
                || lower.contains("effect scan")
                || lower.contains("fallback")
                || lower.contains("disabled");
    }

    private static String actionForReason(String reason) {
        String lower = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (lower.contains("io") || lower.contains("thread") || lower.contains("native") || lower.contains("reflection")) {
            return "keep serial; write manual compat before optimization";
        }
        if (lower.contains("transaction") || lower.contains("unsupported")) {
            return "add sandbox shim or downgrade namespace default";
        }
        if (lower.contains("unknown namespace")) {
            return "classify namespace with registry/effect data";
        }
        return "inspect unit and add guard/parity sample";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Comparator<Map.Entry<String, ?>> countComparator() {
        Comparator<Map.Entry<String, ?>> byCount = (left, right) -> Long.compare(number(right.getValue()), number(left.getValue()));
        Comparator<Map.Entry<String, ?>> byKey = (left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey()));
        return byCount.thenComparing(byKey);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String key) {
        if (source == null) {
            return Map.of();
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
