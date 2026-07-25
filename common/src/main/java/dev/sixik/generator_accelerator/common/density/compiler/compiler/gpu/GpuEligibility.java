package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Conservative JavaToGpu-readiness classifier for DFC IR graphs.
 *
 * <p>This is intentionally diagnostic only. JavaToGpu kernels must be static,
 * array/scalar-oriented, and free of allocation, exceptions, virtual dispatch,
 * object graphs, and general object arrays. DFC's IR is already close to that for
 * pure arithmetic, but several current node families still rely on JVM object
 * payloads or virtual vanilla calls. Those are reported as blockers instead of
 * pretending a GPU backend exists.
 */
public final class GpuEligibility {

    private GpuEligibility() {}

    public enum Blocker {
        VANILLA_NOISE_OBJECT,
        INLINED_NOISE_OBJECT_PAYLOAD,
        BLENDED_NOISE_OBJECT_PAYLOAD,
        SPLINE_OBJECT_PAYLOAD,
        EXTERN_DENSITY_FUNCTION,
        MARKER_BOUNDARY,
        BEARDIFIER_EXTERN,
        END_ISLANDS_EXTERN,
        BLEND_DENSITY_CONTEXT
    }

    public record Report(boolean eligible, int nodesVisited, Map<Blocker, Integer> blockers) {
        public int blockerCount() {
            int total = 0;
            for (int count : blockers.values()) {
                total += count;
            }
            return total;
        }

        public String firstBlocker() {
            for (var entry : blockers.entrySet()) {
                if (entry.getValue() > 0) {
                    return entry.getKey().name();
                }
            }
            return "none";
        }
    }

    public static Report analyze(IRNode root, ConstantPool pool) {
        EnumMap<Blocker, Integer> blockers = new EnumMap<>(Blocker.class);
        IdentityHashMap<IRNode, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<IRNode> stack = new ArrayDeque<>();
        if (root != null) {
            stack.push(root);
        }

        while (!stack.isEmpty()) {
            IRNode node = stack.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            classifyNode(node, pool, blockers);
            for (IRNode child : RefCount.children(node)) {
                stack.push(child);
            }
        }

        if (pool != null) {
            if (pool.splineCount() > 0) {
                add(blockers, Blocker.SPLINE_OBJECT_PAYLOAD, pool.splineCount());
            }
            if (pool.noiseCount() > 0) {
                add(blockers, Blocker.VANILLA_NOISE_OBJECT, pool.noiseCount());
            }
        }

        return new Report(blockers.isEmpty(), visited.size(), Collections.unmodifiableMap(new EnumMap<>(blockers)));
    }

    private static void classifyNode(IRNode node, ConstantPool pool, EnumMap<Blocker, Integer> blockers) {
        if (node instanceof IRNode.Noise
                || node instanceof IRNode.ShiftedNoise
                || node instanceof IRNode.ShiftA
                || node instanceof IRNode.ShiftB
                || node instanceof IRNode.Shift
                || node instanceof IRNode.WeirdScaled) {
            add(blockers, Blocker.VANILLA_NOISE_OBJECT, 1);
        } else if (node instanceof IRNode.Spline.Multipoint) {
            add(blockers, Blocker.SPLINE_OBJECT_PAYLOAD, 1);
        } else if (node instanceof IRNode.Invoke invoke) {
            if (!hasPayloadBuilder(invoke, pool)) {
                add(blockers, Blocker.EXTERN_DENSITY_FUNCTION, 1);
            }
        } else if (node instanceof IRNode.Beardifier) {
            add(blockers, Blocker.BEARDIFIER_EXTERN, 1);
        } else if (node instanceof IRNode.EndIslands) {
            add(blockers, Blocker.END_ISLANDS_EXTERN, 1);
        }
    }

    private static boolean hasPayloadBuilder(IRNode.Invoke invoke, ConstantPool pool) {
        if (pool == null) {
            return false;
        }
        int externIndex = invoke.externIndex();
        if (externIndex < 0 || externIndex >= pool.externCount()) {
            return false;
        }
        return DensityFunctionGpuPayloadBuilderRegistry.hasBuilderFor(pool.extern(externIndex));
    }

    private static void add(EnumMap<Blocker, Integer> blockers, Blocker blocker, int count) {
        blockers.merge(blocker, count, Integer::sum);
    }
}
