package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;

/** Builds {@link GpuIrPayload} for the first JavaToGpu target subset. */
public final class GpuPayloadCompiler {

    private GpuPayloadCompiler() {
    }

    public record Result(
            boolean supported,
            GpuIrPayload payload,
            String firstUnsupportedNode,
            String firstUnsupportedDetail,
            int nodesVisited) {
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

    private static final class Builder implements DensityFunctionGpuPayloadBuilder.Context {
        private final ConstantPool pool;
        private final IdentityHashMap<IRNode, Integer> ids = new IdentityHashMap<>();
        private final IdentityHashMap<CompiledDensityFunction, Integer> inlinedCompiledRoots = new IdentityHashMap<>();
        private final ArrayList<ExternInput> externInputs = new ArrayList<>();
        private final HashMap<ExternInput, Integer> externInputIds = new HashMap<>();
        private final ArrayList<Node> nodes = new ArrayList<>();
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

        private int appendPayload(GpuIrPayload payload, int ownerExternIndex) {
            int offset = nodes.size();
            int[] externInputSlotMap = remapExternInputs(payload, ownerExternIndex);
            for (int i = 0; i < payload.nodeCount(); i++) {
                Node node = Node.of(payload.opcodes()[i]);
                node.arg0 = remapArg(payload.arg0()[i], offset);
                node.arg1 = remapArg(payload.arg1()[i], offset);
                node.arg2 = remapArg(payload.arg2()[i], offset);
                node.int0 = remapExternInputSlot(payload, i, externInputSlotMap);
                node.int1 = payload.int1()[i];
                node.value0 = payload.value0()[i];
                node.value1 = payload.value1()[i];
                node.value2 = payload.value2()[i];
                node.value3 = payload.value3()[i];
                nodes.add(node);
            }
            return offset + payload.rootIndex();
        }

        private int[] remapExternInputs(GpuIrPayload payload, int ownerExternIndex) {
            int inputCount = payload.externInputCount();
            int[] slotMap = new int[inputCount];
            for (int slot = 0; slot < inputCount; slot++) {
                int pathOffset = payload.externInputPathOffsets()[slot];
                int pathLength = payload.externInputPathLengths()[slot];
                int[] ownerPath = new int[pathLength + 1];
                ownerPath[0] = ownerExternIndex;
                if (pathLength > 0) {
                    System.arraycopy(payload.externInputOwnerPath(), pathOffset, ownerPath, 1, pathLength);
                }
                slotMap[slot] = registerExternInput(new ExternInput(
                        ownerPath,
                        payload.externInputLeafExternIndices()[slot]));
            }
            return slotMap;
        }

        private static int remapExternInputSlot(GpuIrPayload payload, int nodeIndex, int[] externInputSlotMap) {
            int int0 = payload.int0()[nodeIndex];
            if (payload.opcodes()[nodeIndex] != GpuIrPayload.EXTERN_INPUT) {
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
                    value3);
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
