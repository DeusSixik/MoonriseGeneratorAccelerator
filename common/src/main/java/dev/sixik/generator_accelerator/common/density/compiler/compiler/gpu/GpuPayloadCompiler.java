package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRBuilder;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IROptimizer;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.NoiseExpander;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import dev.sixik.generator_accelerator.common.density.mixin.noise.ImprovedNoiseAccessor;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Builds {@link GpuIrPayload} for the first JavaToGpu target subset. */
public final class GpuPayloadCompiler {
    private static final String INLINE_CACHE_ONCE_AP2_MARKER_EXTERN_INDICES_PROPERTY =
            "ga.dfc.gpu.inlineCacheOnceAp2MarkerExternIndices";
    private static final String INLINE_CACHE_ONCE_AP2_ALLOW_NESTED_EXTERN_INPUTS_PROPERTY =
            "ga.dfc.gpu.inlineCacheOnceAp2AllowNestedExternInputs";
    private static final AtomicLong INLINE_CACHE_ONCE_AP2_MARKERS_SEEN = new AtomicLong();
    private static final AtomicLong INLINE_CACHE_ONCE_AP2_MARKER_MATCHES = new AtomicLong();
    private static final AtomicLong INLINE_CACHE_ONCE_AP2_MARKER_INLINE_SUCCESSES = new AtomicLong();
    private static final AtomicLong INLINE_CACHE_ONCE_AP2_MARKER_INLINE_FAILURES = new AtomicLong();
    private static final ConcurrentHashMap<String, LongAdder> INLINE_CACHE_ONCE_AP2_MARKER_EXTERN_INDICES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> INLINE_CACHE_ONCE_AP2_MARKER_MATCHED_EXTERN_INDICES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> INLINE_CACHE_ONCE_AP2_MARKER_INLINE_FAILURE_REASONS =
            new ConcurrentHashMap<>();

    private GpuPayloadCompiler() {
    }

    public record Result(
            boolean supported,
            GpuIrPayload payload,
            String firstUnsupportedNode,
            String firstUnsupportedDetail,
            int nodesVisited) {
    }

    public record InlineDiagnostics(
            String inlineCacheOnceAp2MarkerExternIndices,
            boolean inlineCacheOnceAp2AllowNestedExternInputs,
            long cacheOnceAp2MarkersSeen,
            long cacheOnceAp2MarkerMatches,
            long cacheOnceAp2MarkerInlineSuccesses,
            long cacheOnceAp2MarkerInlineFailures,
            List<String> cacheOnceAp2MarkerExternIndices,
            List<String> cacheOnceAp2MarkerMatchedExternIndices,
            List<String> cacheOnceAp2MarkerInlineFailureReasons) {
        public long cacheOnceAp2MarkerMisses() {
            return Math.max(0L, cacheOnceAp2MarkersSeen - cacheOnceAp2MarkerMatches);
        }
    }

    public static Result compile(IRNode root) {
        return compile(root, null);
    }

    public static Result compile(IRNode root, ConstantPool pool) {
        Builder builder = new Builder(pool);
        int rootIndex = builder.visit(root);
        if (rootIndex < 0) {
            return new Result(false, null, builder.firstUnsupportedNode,
                    builder.firstUnsupportedDetail, builder.nodes.size());
        }
        return new Result(true, builder.toPayload(rootIndex), "none", "none", builder.nodes.size());
    }

    public static InlineDiagnostics snapshotInlineDiagnostics() {
        return new InlineDiagnostics(
                System.getProperty(INLINE_CACHE_ONCE_AP2_MARKER_EXTERN_INDICES_PROPERTY, ""),
                Boolean.getBoolean(INLINE_CACHE_ONCE_AP2_ALLOW_NESTED_EXTERN_INPUTS_PROPERTY),
                INLINE_CACHE_ONCE_AP2_MARKERS_SEEN.get(),
                INLINE_CACHE_ONCE_AP2_MARKER_MATCHES.get(),
                INLINE_CACHE_ONCE_AP2_MARKER_INLINE_SUCCESSES.get(),
                INLINE_CACHE_ONCE_AP2_MARKER_INLINE_FAILURES.get(),
                topCounts(INLINE_CACHE_ONCE_AP2_MARKER_EXTERN_INDICES, 8),
                topCounts(INLINE_CACHE_ONCE_AP2_MARKER_MATCHED_EXTERN_INDICES, 8),
                topCounts(INLINE_CACHE_ONCE_AP2_MARKER_INLINE_FAILURE_REASONS, 8));
    }

    public static void resetInlineDiagnostics() {
        INLINE_CACHE_ONCE_AP2_MARKERS_SEEN.set(0L);
        INLINE_CACHE_ONCE_AP2_MARKER_MATCHES.set(0L);
        INLINE_CACHE_ONCE_AP2_MARKER_INLINE_SUCCESSES.set(0L);
        INLINE_CACHE_ONCE_AP2_MARKER_INLINE_FAILURES.set(0L);
        INLINE_CACHE_ONCE_AP2_MARKER_EXTERN_INDICES.clear();
        INLINE_CACHE_ONCE_AP2_MARKER_MATCHED_EXTERN_INDICES.clear();
        INLINE_CACHE_ONCE_AP2_MARKER_INLINE_FAILURE_REASONS.clear();
    }

    static Result compileStandalone(DensityFunction function) {
        return compileStandalone(function, new ConstantPool());
    }

    private static Result compileStandalone(DensityFunction function, ConstantPool pool) {
        if (function == null) {
            return new Result(false, null, "DensityFunction", "DensityFunction:null", 0);
        }
        try {
            ConstantPool childPool = pool == null ? new ConstantPool() : pool;
            IRBuilder builder = new IRBuilder(childPool, CompilingVisitor.global());
            IRNode root = builder.build(function);

            IROptimizer.Result optResult = IROptimizer.optimize(root, builder, childPool);
            root = optResult.root();

            NoiseExpander.Result noiseResult = NoiseExpander.expand(root, builder, childPool);
            root = noiseResult.root();
            if (noiseResult.noisesSpecialized() > 0) {
                IROptimizer.Result postNoise = IROptimizer.optimize(root, builder, childPool);
                root = postNoise.root();
            }

            return compile(root, childPool);
        } catch (Throwable t) {
            return new Result(false, null, "DensityFunction",
                    "DensityFunction:" + function.getClass().getName() + ':' + t.getClass().getSimpleName(), 0);
        }
    }

    private static final class Builder implements DensityFunctionGpuPayloadBuilder.Context {
        private final ConstantPool pool;
        private final IdentityHashMap<IRNode, Integer> ids = new IdentityHashMap<>();
        private final IdentityHashMap<CompiledDensityFunction, Integer> inlinedCompiledRoots = new IdentityHashMap<>();
        private final IdentityHashMap<DensityFunction, GpuIrPayload> pureWrappedPayloads = new IdentityHashMap<>();
        private final IdentityHashMap<DensityFunction, GpuIrPayload> samePoolWrappedPayloads = new IdentityHashMap<>();
        private final IdentityHashMap<NoiseSpec, NoiseRange> noiseRanges = new IdentityHashMap<>();
        private final IdentityHashMap<BlendedNoiseSpec, NoiseRange> blendedNoiseRanges = new IdentityHashMap<>();
        private final ArrayList<ExternInput> externInputs = new ArrayList<>();
        private final HashMap<ExternInput, Integer> externInputIds = new HashMap<>();
        private final ArrayList<Node> nodes = new ArrayList<>();
        private final ArrayList<Integer> noisePermutations = new ArrayList<>();
        private final ArrayList<Double> noiseOctaveData = new ArrayList<>();
        private String firstUnsupportedNode = "none";
        private String firstUnsupportedDetail = "none";

        private Builder(ConstantPool pool) {
            this.pool = pool;
        }

        @Override
        public int visit(IRNode node) {
            if (node == null) {
                return unsupported("null");
            }
            Integer existing = ids.get(node);
            if (existing != null) {
                return existing;
            }

            if (node instanceof IRNode.Invoke invoke) {
                int inlinedRoot = inlineCompiledInvoke(invoke);
                if (inlinedRoot >= 0) {
                    ids.put(node, inlinedRoot);
                }
                return inlinedRoot;
            }

            if (node instanceof IRNode.Marker marker
                    && (inlineRecomputableMarkersEnabled()
                    || inlineCacheOnceMulOrAddMarkersEnabled()
                    || inlineCacheOnceAp2MarkerExternIndicesConfigured())) {
                int inlinedRoot = inlineRecomputableMarker(marker);
                if (inlinedRoot >= 0) {
                    ids.put(node, inlinedRoot);
                    return inlinedRoot;
                }
            }

            Node encoded = encode(node);
            if (encoded == null) {
                return -1;
            }
            int id = nodes.size();
            ids.put(node, id);
            nodes.add(encoded);
            return id;
        }

        private Node encode(IRNode node) {
            if (node instanceof IRNode.Const c) {
                return Node.of(GpuIrPayload.CONST).v0(c.value());
            }
            if (node instanceof IRNode.BlockX) {
                return Node.of(GpuIrPayload.BLOCK_X);
            }
            if (node instanceof IRNode.BlockY) {
                return Node.of(GpuIrPayload.BLOCK_Y);
            }
            if (node instanceof IRNode.BlockZ) {
                return Node.of(GpuIrPayload.BLOCK_Z);
            }
            if (node instanceof IRNode.Bin b) {
                int left = visit(b.left());
                int right = visit(b.right());
                if (left < 0 || right < 0) {
                    return null;
                }
                return Node.of(binOpcode(b.op())).a0(left).a1(right);
            }
            if (node instanceof IRNode.Unary u) {
                int input = visit(u.input());
                if (input < 0) {
                    return null;
                }
                return Node.of(unaryOpcode(u.op())).a0(input);
            }
            if (node instanceof IRNode.Clamp c) {
                int input = visit(c.input());
                if (input < 0) {
                    return null;
                }
                return Node.of(GpuIrPayload.CLAMP).a0(input).v0(c.min()).v1(c.max());
            }
            if (node instanceof IRNode.RangeChoice rc) {
                int input = visit(rc.input());
                int whenIn = visit(rc.whenInRange());
                int whenOut = visit(rc.whenOutOfRange());
                if (input < 0 || whenIn < 0 || whenOut < 0) {
                    return null;
                }
                return Node.of(GpuIrPayload.RANGE_CHOICE)
                        .a0(input).a1(whenIn).a2(whenOut).v0(rc.min()).v1(rc.max());
            }
            if (node instanceof IRNode.YClampedGradient g) {
                return Node.of(GpuIrPayload.Y_CLAMPED_GRADIENT)
                        .i0(g.fromY()).i1(g.toY()).v0(g.fromValue()).v1(g.toValue());
            }
            if (node instanceof IRNode.InlinedNoise in) {
                if (pool == null) {
                    unsupported("InlinedNoise", "InlinedNoise:missing-pool");
                    return null;
                }
                if (in.specPoolIndex() < 0 || in.specPoolIndex() >= pool.noiseSpecCount()) {
                    unsupported("InlinedNoise", "InlinedNoise:spec=" + in.specPoolIndex());
                    return null;
                }
                int x = visit(in.coordX());
                int y = visit(in.coordY());
                int z = visit(in.coordZ());
                if (x < 0 || y < 0 || z < 0) {
                    return null;
                }
                NoiseSpec spec = pool.noiseSpec(in.specPoolIndex());
                NoiseRange range = registerNoiseSpec(in.specPoolIndex(), spec);
                if (range == null) {
                    return null;
                }
                return Node.of(GpuIrPayload.INLINED_NOISE)
                        .a0(x).a1(y).a2(z)
                        .i0(range.offset()).i1(range.length())
                        .v0(spec.valueFactor());
            }
            if (node instanceof IRNode.InlinedBlendedNoise blended) {
                if (pool == null) {
                    unsupported("InlinedBlendedNoise", "InlinedBlendedNoise:missing-pool");
                    return null;
                }
                int specIndex = blended.blendedSpecIndex();
                if (specIndex < 0 || specIndex >= pool.blendedNoiseSpecCount()) {
                    unsupported("InlinedBlendedNoise", "InlinedBlendedNoise:spec=" + specIndex);
                    return null;
                }
                NoiseRange range = registerBlendedNoiseSpec(specIndex, pool.blendedNoiseSpec(specIndex));
                if (range == null) {
                    return null;
                }
                return Node.of(GpuIrPayload.INLINED_BLENDED_NOISE).i0(range.offset()).i1(range.length());
            }
            if (node instanceof IRNode.BlendDensity bd) {
                int input = visit(bd.input());
                if (input < 0) {
                    return null;
                }
                return Node.of(GpuIrPayload.BLEND_DENSITY).a0(input);
            }
            if (node instanceof IRNode.WeirdRarity wr) {
                int input = visit(wr.input());
                if (input < 0) {
                    return null;
                }
                return Node.of(GpuIrPayload.WEIRD_RARITY).a0(input).i0(wr.rarityValueMapperOrdinal());
            }
            if (node instanceof IRNode.Spline.Constant c) {
                return Node.of(GpuIrPayload.CONST).v0(c.value());
            }
            if (node instanceof IRNode.Marker marker) {
                int externInputSlot = registerExternInput(new ExternInput(new int[0], marker.externIndex()));
                return Node.of(GpuIrPayload.EXTERN_INPUT).i0(externInputSlot);
            }

            return unsupported(node.getClass().getSimpleName()) == -1 ? null : null;
        }

        private int inlineCompiledInvoke(IRNode.Invoke invoke) {
            if (pool == null) {
                return unsupported("Invoke", "Invoke:missing-pool");
            }
            int externIndex = invoke.externIndex();
            if (externIndex < 0 || externIndex >= pool.externCount()) {
                return unsupported("Invoke", "Invoke:extern#" + externIndex);
            }

            DensityFunction extern = pool.extern(externIndex);
            if (!(extern instanceof CompiledDensityFunction compiled)) {
                int customRoot = DensityFunctionGpuPayloadBuilderRegistry.tryBuild(extern, this);
                if (customRoot >= 0) {
                    return customRoot;
                }
                if (DensityFunctionGpuPayloadBuilderRegistry.hasBuilderFor(extern)) {
                    return unsupported("Invoke", "InvokePayloadBuilder:"
                            + describeExtern(extern, externIndex) + ":declined");
                }
                return unsupported("Invoke", "Invoke:" + describeExtern(extern, externIndex));
            }

            Integer existingRoot = inlinedCompiledRoots.get(compiled);
            if (existingRoot != null) {
                return existingRoot;
            }

            GpuIrPayload childPayload = GpuPayloadRuntimeRegistry.lookup(compiled);
            if (childPayload == null) {
                return unsupported("Invoke", describeCompiledPayloadBlocker(compiled));
            }

            int copiedRoot = appendPayload(childPayload, externIndex);
            inlinedCompiledRoots.put(compiled, copiedRoot);
            return copiedRoot;
        }

        private int inlineRecomputableMarker(IRNode.Marker marker) {
            if (pool == null) {
                return -1;
            }
            int externIndex = marker.externIndex();
            if (externIndex < 0 || externIndex >= pool.externCount()) {
                return -1;
            }
            DensityFunction extern = pool.extern(externIndex);
            if (!(extern instanceof DensityFunctions.MarkerOrMarked marked)
                    || !isRecomputableMarker(marked.type())) {
                return -1;
            }
            boolean selectiveInlineCacheOnceAp2 = isSelectiveInlineCacheOnceAp2ExternIndex(marked, externIndex);
            if (!inlineRecomputableMarkersEnabled()
                    && !isSelectiveInlineCacheOnceMulOrAdd(marked)
                    && !selectiveInlineCacheOnceAp2) {
                return -1;
            }

            DensityFunction wrapped = marked.wrapped();
            if (wrapped == null) {
                if (selectiveInlineCacheOnceAp2) {
                    recordInlineCacheOnceAp2MarkerInlineFailure("wrapped-null");
                }
                return -1;
            }
            GpuIrPayload payload;
            if (selectiveInlineCacheOnceAp2) {
                payload = inlineCacheOnceAp2AllowNestedExternInputsEnabled()
                        ? samePoolGpuPayload(wrapped)
                        : cachedPureGpuPayload(wrapped);
                if (payload == null) {
                    recordInlineCacheOnceAp2MarkerInlineFailure("payload-null");
                    return -1;
                }
            } else {
                payload = pureWrappedPayloads.get(wrapped);
                if (payload == null) {
                    payload = pureGpuPayload(wrapped);
                    if (payload == null) {
                        return -1;
                    }
                    pureWrappedPayloads.put(wrapped, payload);
                }
            }
            int root = appendPayload(payload, -1);
            if (selectiveInlineCacheOnceAp2) {
                recordInlineCacheOnceAp2MarkerInlineSuccess();
            }
            return root;
        }

        private GpuIrPayload pureGpuPayload(DensityFunction function) {
            GpuIrPayload payload = null;
            if (function instanceof CompiledDensityFunction compiled) {
                payload = GpuPayloadRuntimeRegistry.lookup(compiled);
            }
            if (payload == null) {
                Result result = compileStandalone(function);
                if (result.supported()) {
                    payload = result.payload();
                }
            }
            if (payload == null || payload.hasExternInputs()) {
                return null;
            }
            return payload;
        }

        private GpuIrPayload cachedPureGpuPayload(DensityFunction function) {
            GpuIrPayload payload = pureWrappedPayloads.get(function);
            if (payload == null) {
                payload = pureGpuPayload(function);
                if (payload != null) {
                    pureWrappedPayloads.put(function, payload);
                }
            }
            return payload;
        }

        private GpuIrPayload samePoolGpuPayload(DensityFunction function) {
            if (pool == null || function == null) {
                return null;
            }
            GpuIrPayload cached = samePoolWrappedPayloads.get(function);
            if (cached != null) {
                return cached;
            }
            Result result = compileStandalone(function, pool);
            if (!result.supported() || result.payload() == null) {
                recordInlineCacheOnceAp2MarkerInlineFailure(result.firstUnsupportedDetail());
                return null;
            }
            GpuIrPayload payload = result.payload();
            samePoolWrappedPayloads.put(function, payload);
            return payload;
        }

        private static boolean isRecomputableMarker(DensityFunctions.Marker.Type type) {
            return type == DensityFunctions.Marker.Type.CacheOnce
                    || type == DensityFunctions.Marker.Type.CacheAllInCell;
        }

        private static boolean inlineRecomputableMarkersEnabled() {
            return Boolean.getBoolean("ga.dfc.gpu.inlineRecomputableMarkers");
        }

        private static boolean inlineCacheOnceMulOrAddMarkersEnabled() {
            return Boolean.parseBoolean(System.getProperty(
                    "ga.dfc.gpu.inlineCacheOnceMulOrAddMarkers",
                    "true"));
        }

        private static boolean isSelectiveInlineCacheOnceMulOrAdd(DensityFunctions.MarkerOrMarked marked) {
            return inlineCacheOnceMulOrAddMarkersEnabled()
                    && marked.type() == DensityFunctions.Marker.Type.CacheOnce
                    && marked.wrapped() instanceof DensityFunctions.MulOrAdd;
        }

        private static boolean inlineCacheOnceAp2MarkerExternIndicesConfigured() {
            String value = System.getProperty(INLINE_CACHE_ONCE_AP2_MARKER_EXTERN_INDICES_PROPERTY, "");
            return value != null && !value.isBlank();
        }

        private static boolean inlineCacheOnceAp2AllowNestedExternInputsEnabled() {
            return Boolean.getBoolean(INLINE_CACHE_ONCE_AP2_ALLOW_NESTED_EXTERN_INPUTS_PROPERTY);
        }

        private static boolean isSelectiveInlineCacheOnceAp2ExternIndex(
                DensityFunctions.MarkerOrMarked marked,
                int externIndex) {
            if (marked.type() != DensityFunctions.Marker.Type.CacheOnce
                    || !(marked.wrapped() instanceof DensityFunctions.TwoArgumentSimpleFunction)
                    || marked.wrapped() instanceof DensityFunctions.MulOrAdd) {
                return false;
            }
            boolean selected = inlineCacheOnceAp2MarkerExternIndex(externIndex);
            recordInlineCacheOnceAp2MarkerExternIndex(externIndex, selected);
            return selected;
        }

        private static boolean inlineCacheOnceAp2MarkerExternIndex(int externIndex) {
            String value = System.getProperty(INLINE_CACHE_ONCE_AP2_MARKER_EXTERN_INDICES_PROPERTY, "");
            if (value == null || value.isBlank()) {
                return false;
            }
            String[] parts = value.split(",");
            for (String part : parts) {
                try {
                    if (Integer.parseInt(part.trim()) == externIndex) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed entries so a typo disables only that token.
                }
            }
            return false;
        }

        private static void recordInlineCacheOnceAp2MarkerExternIndex(int externIndex, boolean selected) {
            INLINE_CACHE_ONCE_AP2_MARKERS_SEEN.incrementAndGet();
            addCount(INLINE_CACHE_ONCE_AP2_MARKER_EXTERN_INDICES, Integer.toString(externIndex), 1L);
            if (selected) {
                INLINE_CACHE_ONCE_AP2_MARKER_MATCHES.incrementAndGet();
                addCount(INLINE_CACHE_ONCE_AP2_MARKER_MATCHED_EXTERN_INDICES, Integer.toString(externIndex), 1L);
            }
        }

        private static void recordInlineCacheOnceAp2MarkerInlineSuccess() {
            INLINE_CACHE_ONCE_AP2_MARKER_INLINE_SUCCESSES.incrementAndGet();
        }

        private static void recordInlineCacheOnceAp2MarkerInlineFailure(String reason) {
            INLINE_CACHE_ONCE_AP2_MARKER_INLINE_FAILURES.incrementAndGet();
            addCount(INLINE_CACHE_ONCE_AP2_MARKER_INLINE_FAILURE_REASONS,
                    reason == null || reason.isBlank() ? "unknown" : reason,
                    1L);
        }

        private int appendPayload(GpuIrPayload payload, int ownerExternIndex) {
            int offset = nodes.size();
            int[] externInputSlotMap = remapExternInputs(payload, ownerExternIndex);
            int noiseOctaveOffset = appendNoisePayload(payload);
            for (int i = 0; i < payload.nodeCount(); i++) {
                Node node = Node.of(payload.opcodes()[i]);
                node.arg0 = remapArg(payload.arg0()[i], offset);
                node.arg1 = remapArg(payload.arg1()[i], offset);
                node.arg2 = remapArg(payload.arg2()[i], offset);
                node.int0 = remapInt0(payload, i, externInputSlotMap, noiseOctaveOffset);
                node.int1 = payload.int1()[i];
                node.value0 = payload.value0()[i];
                node.value1 = payload.value1()[i];
                node.value2 = payload.value2()[i];
                node.value3 = payload.value3()[i];
                nodes.add(node);
            }
            return offset + payload.rootIndex();
        }

        private int appendNoisePayload(GpuIrPayload payload) {
            int octaveOffset = noiseOctaveCount();
            for (int value : payload.noisePermutations()) {
                noisePermutations.add(value);
            }
            for (double value : payload.noiseOctaveData()) {
                noiseOctaveData.add(value);
            }
            return octaveOffset;
        }

        private int[] remapExternInputs(GpuIrPayload payload, int ownerExternIndex) {
            int inputCount = payload.externInputCount();
            int[] slotMap = new int[inputCount];
            for (int slot = 0; slot < inputCount; slot++) {
                int pathOffset = payload.externInputPathOffsets()[slot];
                int pathLength = payload.externInputPathLengths()[slot];
                int prepend = ownerExternIndex >= 0 ? 1 : 0;
                int[] ownerPath = new int[pathLength + prepend];
                if (ownerExternIndex >= 0) {
                    ownerPath[0] = ownerExternIndex;
                }
                if (pathLength > 0) {
                    System.arraycopy(payload.externInputOwnerPath(), pathOffset, ownerPath, prepend, pathLength);
                }
                slotMap[slot] = registerExternInput(new ExternInput(
                        ownerPath,
                        payload.externInputLeafExternIndices()[slot]));
            }
            return slotMap;
        }

        private static int remapInt0(
                GpuIrPayload payload,
                int nodeIndex,
                int[] externInputSlotMap,
                int noiseOctaveOffset) {
            int int0 = payload.int0()[nodeIndex];
            int opcode = payload.opcodes()[nodeIndex];
            if (opcode == GpuIrPayload.INLINED_NOISE || opcode == GpuIrPayload.INLINED_BLENDED_NOISE) {
                return int0 + noiseOctaveOffset;
            }
            if (opcode != GpuIrPayload.EXTERN_INPUT) {
                return int0;
            }
            if (int0 < 0 || int0 >= externInputSlotMap.length) {
                return int0;
            }
            return externInputSlotMap[int0];
        }

        private int registerExternInput(ExternInput input) {
            Integer existing = externInputIds.get(input);
            if (existing != null) {
                return existing;
            }
            int next = externInputs.size();
            externInputs.add(input);
            externInputIds.put(input, next);
            return next;
        }

        private NoiseRange registerNoiseSpec(int specIndex, NoiseSpec spec) {
            if (spec == null) {
                unsupported("InlinedNoise", "InlinedNoise:spec#" + specIndex + ":null");
                return null;
            }
            NoiseRange existing = noiseRanges.get(spec);
            if (existing != null) {
                return existing;
            }

            int octaveOffset = noiseOctaveCount();
            int permutationStart = noisePermutations.size();
            int octaveDataStart = noiseOctaveData.size();
            if (!appendPerlinSpec(specIndex, "first", spec.first())
                    || !appendPerlinSpec(specIndex, "second", spec.second())) {
                truncate(noisePermutations, permutationStart);
                truncate(noiseOctaveData, octaveDataStart);
                return null;
            }

            NoiseRange range = new NoiseRange(octaveOffset, noiseOctaveCount() - octaveOffset);
            noiseRanges.put(spec, range);
            return range;
        }

        private NoiseRange registerBlendedNoiseSpec(int specIndex, BlendedNoiseSpec spec) {
            if (spec == null) {
                unsupported("InlinedBlendedNoise", "InlinedBlendedNoise:spec#" + specIndex + ":null");
                return null;
            }
            NoiseRange existing = blendedNoiseRanges.get(spec);
            if (existing != null) {
                return existing;
            }

            int octaveOffset = noiseOctaveCount();
            int permutationStart = noisePermutations.size();
            int octaveDataStart = noiseOctaveData.size();
            appendDummyNoiseOctave(spec.xzMultiplier(), spec.yMultiplier(), spec.xzFactor(), spec.yFactor(), spec.smearScaleMultiplier());
            if (!appendBlendedOctaves(specIndex, "main", spec.mainOctaves(), BlendedNoiseSpec.MAIN_OCTAVES)
                    || !appendBlendedOctaves(specIndex, "min", spec.minLimitOctaves(), BlendedNoiseSpec.LIMIT_OCTAVES)
                    || !appendBlendedOctaves(specIndex, "max", spec.maxLimitOctaves(), BlendedNoiseSpec.LIMIT_OCTAVES)) {
                truncate(noisePermutations, permutationStart);
                truncate(noiseOctaveData, octaveDataStart);
                return null;
            }

            int length = noiseOctaveCount() - octaveOffset;
            NoiseRange range = new NoiseRange(octaveOffset, length);
            blendedNoiseRanges.put(spec, range);
            return range;
        }

        private boolean appendBlendedOctaves(
                int specIndex,
                String branchName,
                ImprovedNoise[] octaves,
                int expectedLength) {
            if (octaves == null || octaves.length != expectedLength) {
                unsupported("InlinedBlendedNoise", "InlinedBlendedNoise:spec#" + specIndex
                        + ":" + branchName + ":malformed");
                return false;
            }
            double frequency = 1.0D;
            for (int i = 0; i < expectedLength; i++) {
                ImprovedNoise octave = octaves[i];
                if (octave == null) {
                    appendDummyNoiseOctave(frequency, 0.0D, 0.0D, 0.0D, 0.0D);
                } else if (!appendNoiseOctave(
                        specIndex,
                        "blended-" + branchName,
                        i,
                        octave,
                        frequency,
                        1.0D / frequency)) {
                    return false;
                }
                frequency *= 0.5D;
            }
            return true;
        }

        private void appendDummyNoiseOctave(
                double inputFactor,
                double ampValueFactor,
                double xo,
                double yo,
                double zo) {
            for (int i = 0; i < GpuIrPayload.NOISE_PERMUTATION_SIZE; i++) {
                noisePermutations.add(0);
            }
            noiseOctaveData.add(inputFactor);
            noiseOctaveData.add(ampValueFactor);
            noiseOctaveData.add(xo);
            noiseOctaveData.add(yo);
            noiseOctaveData.add(zo);
        }

        private boolean appendPerlinSpec(int specIndex, String branchName, NoiseSpec.PerlinSpec branch) {
            if (branch == null) {
                unsupported("InlinedNoise", "InlinedNoise:spec#" + specIndex + ":" + branchName + ":null");
                return false;
            }
            ImprovedNoise[] octaves = branch.activeOctaves();
            double[] inputFactors = branch.inputFactors();
            double[] ampValueFactors = branch.ampValueFactors();
            if (octaves == null || inputFactors == null || ampValueFactors == null
                    || octaves.length != inputFactors.length
                    || octaves.length != ampValueFactors.length) {
                unsupported("InlinedNoise", "InlinedNoise:spec#" + specIndex + ":" + branchName + ":malformed");
                return false;
            }

            double coordScale = branch.inputCoordScale();
            for (int i = 0; i < octaves.length; i++) {
                if (!appendNoiseOctave(
                        specIndex,
                        branchName,
                        i,
                        octaves[i],
                        coordScale * inputFactors[i],
                        ampValueFactors[i])) {
                    return false;
                }
            }
            return true;
        }

        private boolean appendNoiseOctave(
                int specIndex,
                String branchName,
                int octaveIndex,
                ImprovedNoise octave,
                double inputFactor,
                double ampValueFactor) {
            if (octave == null) {
                unsupported("InlinedNoise", "InlinedNoise:spec#" + specIndex + ":" + branchName
                        + ":octave#" + octaveIndex + ":null");
                return false;
            }

            ImprovedNoiseAccessor accessor;
            try {
                accessor = (ImprovedNoiseAccessor) (Object) octave;
            } catch (ClassCastException exception) {
                unsupported("InlinedNoise", "InlinedNoise:spec#" + specIndex + ":" + branchName
                        + ":octave#" + octaveIndex + ":missing-accessor");
                return false;
            }

            byte[] permutation = accessor.dfc$getPermutation();
            if (permutation == null || permutation.length != GpuIrPayload.NOISE_PERMUTATION_SIZE) {
                unsupported("InlinedNoise", "InlinedNoise:spec#" + specIndex + ":" + branchName
                        + ":octave#" + octaveIndex + ":permutation-length="
                        + (permutation == null ? -1 : permutation.length));
                return false;
            }
            for (byte value : permutation) {
                noisePermutations.add(value & 0xFF);
            }
            noiseOctaveData.add(inputFactor);
            noiseOctaveData.add(ampValueFactor);
            noiseOctaveData.add(accessor.dfc$getXo());
            noiseOctaveData.add(accessor.dfc$getYo());
            noiseOctaveData.add(accessor.dfc$getZo());
            return true;
        }

        private int noiseOctaveCount() {
            return noiseOctaveData.size() / GpuIrPayload.NOISE_OCTAVE_DATA_STRIDE;
        }

        private static <T> void truncate(ArrayList<T> list, int size) {
            while (list.size() > size) {
                list.remove(list.size() - 1);
            }
        }

        private static int remapArg(int arg, int offset) {
            return arg < 0 ? arg : offset + arg;
        }

        private static String describeExtern(DensityFunction extern, int externIndex) {
            if (extern == null) {
                return "extern#" + externIndex + ":null";
            }
            return extern.getClass().getName();
        }

        private static String describeCompiledPayloadBlocker(CompiledDensityFunction compiled) {
            GpuPayloadRuntimeRegistry.Diagnostics diagnostics = GpuPayloadRuntimeRegistry.diagnostics(compiled);
            String detail = diagnostics == null ? "payload-missing" : diagnostics.firstUnsupportedDetail();
            if (detail == null || detail.isBlank() || "none".equals(detail)) {
                detail = diagnostics == null ? "payload-missing" : diagnostics.firstEligibilityBlocker();
            }
            if (detail == null || detail.isBlank() || "none".equals(detail)) {
                detail = "payload-missing";
            }
            return "InvokeCompiled:" + compiled.getClass().getName() + "->" + detail;
        }

        @Override
        public ConstantPool pool() {
            return pool;
        }

        @Override
        public int externInput(DensityFunction function) {
            if (pool == null) {
                return unsupported("ExternInput", "ExternInput:missing-pool");
            }
            if (function == null) {
                return unsupported("ExternInput", "ExternInput:null");
            }
            int externIndex = pool.internExtern(function);
            int externInputSlot = registerExternInput(new ExternInput(new int[0], externIndex));
            return appendNode(Node.of(GpuIrPayload.EXTERN_INPUT).i0(externInputSlot));
        }

        @Override
        public int constant(double value) {
            return appendNode(Node.of(GpuIrPayload.CONST).v0(value));
        }

        @Override
        public int blockX() {
            return appendNode(Node.of(GpuIrPayload.BLOCK_X));
        }

        @Override
        public int blockY() {
            return appendNode(Node.of(GpuIrPayload.BLOCK_Y));
        }

        @Override
        public int blockZ() {
            return appendNode(Node.of(GpuIrPayload.BLOCK_Z));
        }

        @Override
        public int bin(IRNode.BinOp op, int left, int right) {
            if (left < 0 || right < 0) {
                return unsupported("PayloadBuilder", "PayloadBuilder:negative-bin-arg");
            }
            return appendNode(Node.of(binOpcode(op)).a0(left).a1(right));
        }

        @Override
        public int unary(IRNode.UnaryOp op, int input) {
            if (input < 0) {
                return unsupported("PayloadBuilder", "PayloadBuilder:negative-unary-arg");
            }
            return appendNode(Node.of(unaryOpcode(op)).a0(input));
        }

        @Override
        public int clamp(int input, double min, double max) {
            if (input < 0) {
                return unsupported("PayloadBuilder", "PayloadBuilder:negative-clamp-arg");
            }
            return appendNode(Node.of(GpuIrPayload.CLAMP).a0(input).v0(min).v1(max));
        }

        @Override
        public int rangeChoice(int input, double min, double max, int whenInRange, int whenOutOfRange) {
            if (input < 0 || whenInRange < 0 || whenOutOfRange < 0) {
                return unsupported("PayloadBuilder", "PayloadBuilder:negative-range-choice-arg");
            }
            return appendNode(Node.of(GpuIrPayload.RANGE_CHOICE)
                    .a0(input).a1(whenInRange).a2(whenOutOfRange).v0(min).v1(max));
        }

        @Override
        public int yClampedGradient(int fromY, int toY, double fromValue, double toValue) {
            return appendNode(Node.of(GpuIrPayload.Y_CLAMPED_GRADIENT)
                    .i0(fromY).i1(toY).v0(fromValue).v1(toValue));
        }

        @Override
        public int customOp(
                String opId,
                int arg0,
                int arg1,
                int arg2,
                double param0,
                double param1,
                double param2,
                double param3) {
            DensityFunctionGpuKernelOpRegistry.Entry entry = DensityFunctionGpuKernelOpRegistry.lookup(opId);
            if (entry == null) {
                return unsupported("CustomGpuOp", "CustomGpuOp:" + normalizeOpId(opId) + ":missing");
            }
            DensityFunctionGpuKernelOp op = entry.op();
            int inputCount = op.inputCount();
            if ((inputCount > 0 && arg0 < 0)
                    || (inputCount > 1 && arg1 < 0)
                    || (inputCount > 2 && arg2 < 0)) {
                return unsupported("CustomGpuOp", "CustomGpuOp:" + op.id() + ":negative-arg");
            }
            if (op.parameterCount() < 1 && param0 != 0.0D
                    || op.parameterCount() < 2 && param1 != 0.0D
                    || op.parameterCount() < 3 && param2 != 0.0D
                    || op.parameterCount() < 4 && param3 != 0.0D) {
                return unsupported("CustomGpuOp", "CustomGpuOp:" + op.id() + ":extra-parameter");
            }

            // The current runtime launches the fixed JavaToGpu arithmetic kernel. Keep
            // arbitrary source fragments visible to diagnostics, but do not pack them
            // into a GPU-runnable payload until a generated-source launcher is wired.
            return unsupported("CustomGpuOp", "CustomGpuOp:" + op.id() + ":generated-kernel-required");
        }

        private static String normalizeOpId(String opId) {
            return opId == null || opId.isBlank() ? "blank" : opId.trim();
        }

        private int appendNode(Node node) {
            int id = nodes.size();
            nodes.add(node);
            return id;
        }

        private int unsupported(String nodeName) {
            return unsupported(nodeName, nodeName);
        }

        private int unsupported(String nodeName, String detail) {
            if ("none".equals(firstUnsupportedNode)) {
                firstUnsupportedNode = nodeName;
                firstUnsupportedDetail = detail == null || detail.isBlank() ? nodeName : detail;
            }
            return -1;
        }

        private GpuIrPayload toPayload(int rootIndex) {
            int n = nodes.size();
            int externInputCount = externInputs.size();
            int[] externInputPathOffsets = new int[externInputCount];
            int[] externInputPathLengths = new int[externInputCount];
            int[] externInputLeafExternIndices = new int[externInputCount];
            int totalPathLength = 0;
            for (ExternInput input : externInputs) {
                totalPathLength += input.ownerPath.length;
            }
            int[] externInputOwnerPath = new int[totalPathLength];
            int pathCursor = 0;
            for (int i = 0; i < externInputCount; i++) {
                ExternInput input = externInputs.get(i);
                externInputPathOffsets[i] = pathCursor;
                externInputPathLengths[i] = input.ownerPath.length;
                externInputLeafExternIndices[i] = input.leafExternIndex;
                System.arraycopy(input.ownerPath, 0, externInputOwnerPath, pathCursor, input.ownerPath.length);
                pathCursor += input.ownerPath.length;
            }
            int[] opcodes = new int[n];
            int[] arg0 = new int[n];
            int[] arg1 = new int[n];
            int[] arg2 = new int[n];
            int[] int0 = new int[n];
            int[] int1 = new int[n];
            double[] value0 = new double[n];
            double[] value1 = new double[n];
            double[] value2 = new double[n];
            double[] value3 = new double[n];
            for (int i = 0; i < n; i++) {
                Node node = nodes.get(i);
                opcodes[i] = node.opcode;
                arg0[i] = node.arg0;
                arg1[i] = node.arg1;
                arg2[i] = node.arg2;
                int0[i] = node.int0;
                int1[i] = node.int1;
                value0[i] = node.value0;
                value1[i] = node.value1;
                value2[i] = node.value2;
                value3[i] = node.value3;
            }
            return new GpuIrPayload(
                    rootIndex,
                    externInputCount,
                    externInputPathOffsets,
                    externInputPathLengths,
                    externInputOwnerPath,
                    externInputLeafExternIndices,
                    opcodes,
                    arg0,
                    arg1,
                    arg2,
                    int0,
                    int1,
                    value0,
                    value1,
                    value2,
                    value3,
                    toIntArray(noisePermutations),
                    toDoubleArray(noiseOctaveData));
        }

        private static int[] toIntArray(ArrayList<Integer> values) {
            int[] result = new int[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i);
            }
            return result;
        }

        private static double[] toDoubleArray(ArrayList<Double> values) {
            double[] result = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i);
            }
            return result;
        }

        private static int binOpcode(IRNode.BinOp op) {
            return switch (op) {
                case ADD -> GpuIrPayload.ADD;
                case SUB -> GpuIrPayload.SUB;
                case MUL -> GpuIrPayload.MUL;
                case DIV -> GpuIrPayload.DIV;
                case MIN -> GpuIrPayload.MIN;
                case MAX -> GpuIrPayload.MAX;
            };
        }

        private static int unaryOpcode(IRNode.UnaryOp op) {
            return switch (op) {
                case ABS -> GpuIrPayload.ABS;
                case NEG -> GpuIrPayload.NEG;
                case SQUARE -> GpuIrPayload.SQUARE;
                case CUBE -> GpuIrPayload.CUBE;
                case HALF_NEGATIVE -> GpuIrPayload.HALF_NEGATIVE;
                case QUARTER_NEGATIVE -> GpuIrPayload.QUARTER_NEGATIVE;
                case SQUEEZE -> GpuIrPayload.SQUEEZE;
            };
        }
    }

    private record NoiseRange(int offset, int length) {
    }

    private static void addCount(ConcurrentHashMap<String, LongAdder> counts, String key, long amount) {
        if (key == null || key.isBlank() || amount <= 0L) {
            return;
        }
        counts.computeIfAbsent(key, ignored -> new LongAdder()).add(amount);
    }

    private static List<String> topCounts(ConcurrentHashMap<String, LongAdder> counts, int limit) {
        ArrayList<String> out = new ArrayList<>();
        counts.entrySet().stream()
                .map(entry -> new CountEntry(entry.getKey(), entry.getValue().sum()))
                .filter(entry -> entry.count() > 0L)
                .sorted(Comparator.comparingLong(CountEntry::count).reversed()
                        .thenComparing(CountEntry::key))
                .limit(limit)
                .forEach(entry -> out.add(entry.key() + "=" + entry.count()));
        return out;
    }

    private record CountEntry(String key, long count) {
    }

    private static final class ExternInput {
        private final int[] ownerPath;
        private final int leafExternIndex;

        private ExternInput(int[] ownerPath, int leafExternIndex) {
            this.ownerPath = ownerPath == null ? new int[0] : ownerPath.clone();
            this.leafExternIndex = leafExternIndex;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ExternInput input)) {
                return false;
            }
            return leafExternIndex == input.leafExternIndex && Arrays.equals(ownerPath, input.ownerPath);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(ownerPath) + leafExternIndex;
        }
    }

    private static final class Node {
        private final int opcode;
        private int arg0 = -1;
        private int arg1 = -1;
        private int arg2 = -1;
        private int int0;
        private int int1;
        private double value0;
        private double value1;
        private double value2;
        private double value3;

        private Node(int opcode) {
            this.opcode = opcode;
        }

        static Node of(int opcode) {
            return new Node(opcode);
        }

        Node a0(int value) { this.arg0 = value; return this; }
        Node a1(int value) { this.arg1 = value; return this; }
        Node a2(int value) { this.arg2 = value; return this; }
        Node i0(int value) { this.int0 = value; return this; }
        Node i1(int value) { this.int1 = value; return this; }
        Node v0(double value) { this.value0 = value; return this; }
        Node v1(double value) { this.value1 = value; return this; }
        Node v2(double value) { this.value2 = value; return this; }
        Node v3(double value) { this.value3 = value; return this; }
    }
}
