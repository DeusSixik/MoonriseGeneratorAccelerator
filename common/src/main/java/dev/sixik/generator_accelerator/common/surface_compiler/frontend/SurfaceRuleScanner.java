package dev.sixik.generator_accelerator.common.surface_compiler.frontend;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceNode;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterDescriptor;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterRegistry;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterSafetyClass;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class SurfaceRuleScanner {
    private static final String MC_PREFIX = "net.minecraft.";

    private final AdapterRegistry adapters;

    public SurfaceRuleScanner() {
        this(new AdapterRegistry());
    }

    public SurfaceRuleScanner(AdapterRegistry adapters) {
        this.adapters = adapters;
    }

    public SurfaceScanResult scan(SurfaceRules.RuleSource source) {
        if (source == null) {
            return new SurfaceScanResult("null", true, true, SurfaceNode.opaque("null", "null rule source"), 0, 1, 0,
                    List.of(new OpaqueNode("null", "null rule source", true, false)));
        }

        ScanState state = new ScanState();
        SurfaceNode root = scanRule(source, state, new IdentityHashMap<>());
        boolean vanillaOwned = source.getClass().getName().startsWith(MC_PREFIX);
        return new SurfaceScanResult(source.getClass().getName(), vanillaOwned, state.opaqueCallouts > 0, root,
                state.vanillaNodes, state.opaqueCallouts, state.statefulNodes, state.opaqueNodes);
    }

    private SurfaceNode scanRule(SurfaceRules.RuleSource source, ScanState state, Map<Object, Boolean> seen) {
        if (source == null) {
            return opaqueRule("null", "null rule", true, state);
        }
        if (seen.put(source, Boolean.TRUE) != null) {
            String className = source.getClass().getName();
            return opaqueRule(className, "cyclic rule graph", className.startsWith(MC_PREFIX), state);
        }

        String className = source.getClass().getName();
        if (!className.startsWith(MC_PREFIX)) {
            AdapterDescriptor descriptor = this.adapters.find(className).map(adapter -> adapter.descriptor()).orElse(null);
            if (descriptor != null && descriptor.primitiveAbi() && descriptor.safetyClass() != AdapterSafetyClass.UNSAFE && descriptor.safetyClass() != AdapterSafetyClass.MUTATING_OR_UNKNOWN) {
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.adapterCallout(className, effectFor(descriptor.safetyClass()), domainFor(descriptor.safetyClass()),
                        "adapter=" + descriptor.id()
                                + ",version=" + descriptor.version()
                                + ",safety=" + descriptor.safetyClass()
                                + ",vector=" + descriptor.vectorAbi()
                                + ",width=" + descriptor.vectorWidth()
                                + ",cert=" + descriptor.certificationId());
            }
            return opaqueRule(className, "external rule source", false, state);
        }

        try {
            if (source == SurfaceRules.Bandlands.INSTANCE) {
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.vanillaCallout(SurfaceNode.Kind.BANDLANDS, SurfaceEffect.READ_ONLY_ORDERED,
                        SurfaceDomain.OPAQUE, className, "vanilla bandlands material callout");
            }
            if (hasNoArg(source, "resultState")) {
                state.vanillaNodes++;
                return SurfaceNode.state((net.minecraft.world.level.block.state.BlockState) invoke(source, "resultState"), className);
            }
            if (hasNoArg(source, "sequence")) {
                List<?> raw = (List<?>) invoke(source, "sequence");
                List<SurfaceNode> children = new ArrayList<>(raw.size());
                for (Object child : raw) {
                    children.add(scanRule((SurfaceRules.RuleSource) child, state, seen));
                }
                state.vanillaNodes++;
                return SurfaceNode.sequence(children, className);
            }
            if (hasNoArg(source, "ifTrue") && hasNoArg(source, "thenRun")) {
                SurfaceNode condition = scanCondition((SurfaceRules.ConditionSource) invoke(source, "ifTrue"), state, seen);
                SurfaceNode thenRun = scanRule((SurfaceRules.RuleSource) invoke(source, "thenRun"), state, seen);
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.test(condition, thenRun, className);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return opaqueRule(className, "scanner failure: " + e.getClass().getSimpleName(), true, state);
        } finally {
            seen.remove(source);
        }

        return opaqueRule(className, "unknown vanilla rule source", true, state);
    }

    private SurfaceNode scanCondition(SurfaceRules.ConditionSource source, ScanState state, Map<Object, Boolean> seen) throws ReflectiveOperationException {
        if (source == null) {
            return opaqueCondition("null", "null condition", true, state);
        }
        if (seen.put(source, Boolean.TRUE) != null) {
            String className = source.getClass().getName();
            return opaqueCondition(className, "cyclic condition graph", className.startsWith(MC_PREFIX), state);
        }

        String className = source.getClass().getName();
        try {
            if (!className.startsWith(MC_PREFIX)) {
                return opaqueCondition(className, "external condition source", false, state);
            }
            if (hasNoArg(source, "target")) {
                SurfaceNode child = scanCondition((SurfaceRules.ConditionSource) invoke(source, "target"), state, seen);
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.condition(SurfaceNode.Kind.NOT, SurfaceEffect.READ_ONLY_ORDERED, child.domain(), className, "not", List.of(child));
            }
            if (hasNoArg(source, "offset") && hasNoArg(source, "surfaceDepthMultiplier")) {
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.condition(SurfaceNode.Kind.WATER_CHECK, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, className,
                        "offset=" + invoke(source, "offset") + ",surfaceDepthMultiplier=" + invoke(source, "surfaceDepthMultiplier"), List.of());
            }
            if (hasNoArg(source, "anchor") && hasNoArg(source, "surfaceDepthMultiplier")) {
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.condition(SurfaceNode.Kind.Y_CHECK, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.Y_BAND, className,
                        "anchor=" + invoke(source, "anchor") + ",surfaceDepthMultiplier=" + invoke(source, "surfaceDepthMultiplier"), List.of());
            }
            if (className.contains("StoneDepth")) {
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.condition(SurfaceNode.Kind.STONE_DEPTH, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.STONE_DEPTH, className, className, List.of());
            }
            if (className.contains("Noise")) {
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.condition(SurfaceNode.Kind.NOISE, SurfaceEffect.STATEFUL_NOISE, SurfaceDomain.NOISE, className, className, List.of());
            }
            if (className.contains("Biome")) {
                state.vanillaNodes++;
                state.statefulNodes++;
                return SurfaceNode.condition(SurfaceNode.Kind.BIOME, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.BIOME, className, className, List.of());
            }
            state.vanillaNodes++;
            state.statefulNodes++;
            return SurfaceNode.condition(SurfaceNode.Kind.VANILLA_CONSTANT, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.OPAQUE, className, className, List.of());
        } finally {
            seen.remove(source);
        }
    }

    private static SurfaceNode opaqueRule(String className, String reason, boolean vanillaOwned, ScanState state) {
        state.opaqueCallouts++;
        state.opaqueNodes.add(new OpaqueNode(className, reason, vanillaOwned, false));
        return SurfaceNode.opaque(className, reason);
    }

    private static SurfaceNode opaqueCondition(String className, String reason, boolean vanillaOwned, ScanState state) {
        state.opaqueCallouts++;
        state.opaqueNodes.add(new OpaqueNode(className, reason, vanillaOwned, true));
        return SurfaceNode.opaque(className, reason);
    }

    private static boolean hasNoArg(Object target, String name) {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        try {
            return method.invoke(target);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static SurfaceEffect effectFor(AdapterSafetyClass safetyClass) {
        return switch (safetyClass) {
            case EXACT_INLINE, READ_ONLY_CERTIFIED_VECTOR -> SurfaceEffect.READ_ONLY_STABLE;
            case EXACT_ORDERED_INLINE,
                 READ_ONLY_COMPILER_ITERATED_SCALAR,
                 READ_ONLY_LEGACY_BLOCKPOS,
                 HALO_READ_ONLY,
                 CONTEXT_SENSITIVE,
                 ORDERED_OPAQUE -> SurfaceEffect.READ_ONLY_ORDERED;
            case MUTATING_OR_UNKNOWN, UNSAFE -> SurfaceEffect.OPAQUE_CALLOUT;
        };
    }

    private static SurfaceDomain domainFor(AdapterSafetyClass safetyClass) {
        return switch (safetyClass) {
            case HALO_READ_ONLY -> SurfaceDomain.HALO;
            case CONTEXT_SENSITIVE -> SurfaceDomain.OPAQUE;
            default -> SurfaceDomain.OPAQUE;
        };
    }

    private static final class ScanState {
        private int vanillaNodes;
        private int opaqueCallouts;
        private int statefulNodes;
        private final List<OpaqueNode> opaqueNodes = new ArrayList<>();
    }

    public record SurfaceScanResult(String rootClassName, boolean vanillaOwned, boolean containsOpaqueCallouts, SurfaceNode root,
                                    int vanillaNodes, int opaqueCallouts, int statefulNodes, List<OpaqueNode> opaqueNodes) {
        public SurfaceScanResult(String rootClassName, boolean vanillaOwned, boolean containsOpaqueCallouts, SurfaceNode root,
                                 int vanillaNodes, int opaqueCallouts, int statefulNodes) {
            this(rootClassName, vanillaOwned, containsOpaqueCallouts, root, vanillaNodes, opaqueCallouts, statefulNodes, List.of());
        }

    }

    public record OpaqueNode(String sourceClassName, String reason, boolean vanillaOwned, boolean condition) {
    }
}
