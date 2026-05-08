package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles the lattice {@code lattice_inner} expression (with hoisted Y subtree replaced by
 * a parameter and inlined noises by slab slot indices) into a compact postfix program executed
 * by {@code dfc_slab_inner_eval_batch} in {@code dfc-natives}.
 */
public final class SlabInnerNativeProgram {

    static final byte OP_PUSH_CONST = 1;
    static final byte OP_PUSH_SLOT = 2;
    static final byte OP_COND_NEG_SCALE = 3;
    static final byte OP_Y_CLAMPED_GRADIENT = 4;
    static final byte OP_RANGE_CHOICE = 5;
    static final byte OP_BLOCK_X = 16;
    static final byte OP_BLOCK_Y = 17;
    static final byte OP_BLOCK_Z = 18;
    static final byte OP_HOIST = 19;
    static final byte OP_ADD = 32;
    static final byte OP_SUB = 33;
    static final byte OP_MUL = 34;
    static final byte OP_DIV = 35;
    static final byte OP_MIN = 36;
    static final byte OP_MAX = 37;
    static final byte OP_NEG = 48;
    static final byte OP_ABS = 49;
    static final byte OP_SQUARE = 50;
    static final byte OP_SQUEEZE = 51;

    private static final int VM_STACK_LIMIT = 192;

    public record Result(byte[] bytecode, double[] constants, boolean applyBlendDensity) {}

    private SlabInnerNativeProgram() {}

    public static Optional<Result> tryCompile(IRNode root, CellLatticeOption.LatticePlan plan,
                                              SlabNativeBatchPlan slabPlan, Set<IRNode> extracted) {
        if (plan == null || slabPlan == null || slabPlan.isEmpty()) {
            return Optional.empty();
        }
        IdentityHashMap<IRNode, Integer> slabSlots = new IdentityHashMap<>();
        for (SlabNativeBatchPlan.Slot s : slabPlan.slots()) {
            IRNode key = switch (s) {
                case SlabNativeBatchPlan.NormalSlot ns -> ns.noise();
                case SlabNativeBatchPlan.BlendedSlot bs -> bs.noise();
                case SlabNativeBatchPlan.MarkerSlot ms -> ms.marker();
            };
            slabSlots.put(key, s.slotIndex());
        }
        boolean applyBlendDensity = root instanceof IRNode.BlendDensity;
        IRNode compileRoot = applyBlendDensity ? ((IRNode.BlendDensity) root).input() : root;
        var b = new Builder();
        try {
            if (!b.compile(compileRoot, plan.hoistedSubtree(), extracted, slabSlots)) {
                b.recordFailure();
                return Optional.empty();
            }
        } catch (IOException e) {
            DfcNativePlanningStats.recordSlabInnerMissingIo();
            return Optional.empty();
        }
        byte[] bytecode = b.bytes();
        if (!isValidStackProgram(bytecode, b.constCount())) {
            DfcNativePlanningStats.recordSlabInnerMissingInvalidProgram();
            return Optional.empty();
        }
        return Optional.of(new Result(bytecode, b.consts(), applyBlendDensity));
    }

    private static boolean isValidStackProgram(byte[] bc, int constCount) {
        int depth = 0;
        for (int pc = 0; pc < bc.length;) {
            int op = bc[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST -> {
                    if (pc + 2 > bc.length) {
                        return false;
                    }
                    int idx = (bc[pc] & 0xFF) | ((bc[pc + 1] & 0xFF) << 8);
                    pc += 2;
                    if (idx >= constCount) {
                        return false;
                    }
                    depth++;
                }
                case OP_PUSH_SLOT -> {
                    if (pc >= bc.length) {
                        return false;
                    }
                    pc++;
                    depth++;
                }
                case OP_Y_CLAMPED_GRADIENT -> {
                    if (pc + 8 > bc.length) {
                        return false;
                    }
                    for (int i = 0; i < 4; i++) {
                        int idx = (bc[pc] & 0xFF) | ((bc[pc + 1] & 0xFF) << 8);
                        pc += 2;
                        if (idx >= constCount) {
                            return false;
                        }
                    }
                    depth++;
                }
                case OP_RANGE_CHOICE -> {
                    if (pc + 4 > bc.length || depth < 3) {
                        return false;
                    }
                    for (int i = 0; i < 2; i++) {
                        int idx = (bc[pc] & 0xFF) | ((bc[pc + 1] & 0xFF) << 8);
                        pc += 2;
                        if (idx >= constCount) {
                            return false;
                        }
                    }
                    depth -= 2;
                }
                case OP_BLOCK_X, OP_BLOCK_Y, OP_BLOCK_Z, OP_HOIST -> depth++;
                case OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX -> {
                    if (depth < 2) {
                        return false;
                    }
                    depth--;
                }
                case OP_COND_NEG_SCALE -> {
                    if (pc + 2 > bc.length || depth < 1) {
                        return false;
                    }
                    int idx = (bc[pc] & 0xFF) | ((bc[pc + 1] & 0xFF) << 8);
                    pc += 2;
                    if (idx >= constCount) {
                        return false;
                    }
                }
                case OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                    if (depth < 1) {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
            if (depth > VM_STACK_LIMIT) {
                return false;
            }
        }
        return depth == 1;
    }

    private static final class Builder {
        private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        private final List<Double> constList = new ArrayList<>();
        private String unsupportedClass;
        private boolean failedOnExtracted;

        byte[] bytes() {
            return raw.toByteArray();
        }

        double[] consts() {
            double[] a = new double[constList.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = constList.get(i);
            }
            return a;
        }

        int constCount() {
            return constList.size();
        }

        int addConst(double v) {
            int idx = constList.size();
            constList.add(v);
            return idx;
        }

        void writeLeU16(int idx) throws IOException {
            raw.write(idx & 0xFF);
            raw.write((idx >> 8) & 0xFF);
        }

        void emitConst(double v) throws IOException {
            raw.write(OP_PUSH_CONST);
            writeLeU16(addConst(v));
        }

        void emitSlot(int slot) throws IOException {
            raw.write(OP_PUSH_SLOT);
            raw.write(slot);
        }

        void recordFailure() {
            if (this.failedOnExtracted) {
                DfcNativePlanningStats.recordSlabInnerMissingExtracted();
            } else {
                DfcNativePlanningStats.recordSlabInnerMissingUnsupportedNode(
                        this.unsupportedClass != null ? this.unsupportedClass : "unknown");
            }
        }

        boolean compile(IRNode node, IRNode hoisted, Set<IRNode> extracted,
                        IdentityHashMap<IRNode, Integer> slabSlots) throws IOException {
            if (node == hoisted) {
                raw.write(OP_HOIST);
                return true;
            }
            if (extracted.contains(node)) {
                this.failedOnExtracted = true;
                this.unsupportedClass = node.getClass().getName();
                return false;
            }
            Integer slab = slabSlots.get(node);
            if (slab != null) {
                emitSlot(slab);
                return true;
            }
            if (node instanceof IRNode.Const c) {
                emitConst(c.value());
                return true;
            }
            if (node instanceof IRNode.BlockX) {
                raw.write(OP_BLOCK_X);
                return true;
            }
            if (node instanceof IRNode.BlockY) {
                raw.write(OP_BLOCK_Y);
                return true;
            }
            if (node instanceof IRNode.BlockZ) {
                raw.write(OP_BLOCK_Z);
                return true;
            }
            if (node instanceof IRNode.YClampedGradient g) {
                return emitYClampedGradient(g);
            }
            if (node instanceof IRNode.RangeChoice rc) {
                return emitRangeChoice(rc, hoisted, extracted, slabSlots);
            }
            if (node instanceof IRNode.WeirdRarity wr) {
                return emitWeirdRarity(wr, hoisted, extracted, slabSlots);
            }
            if (node instanceof IRNode.Bin bin) {
                if (!compile(bin.left(), hoisted, extracted, slabSlots)) {
                    return false;
                }
                if (!compile(bin.right(), hoisted, extracted, slabSlots)) {
                    return false;
                }
                raw.write(switch (bin.op()) {
                    case ADD -> OP_ADD;
                    case SUB -> OP_SUB;
                    case MUL -> OP_MUL;
                    case DIV -> OP_DIV;
                    case MIN -> OP_MIN;
                    case MAX -> OP_MAX;
                });
                return true;
            }
            if (node instanceof IRNode.Unary u) {
                switch (u.op()) {
                    case CUBE -> {
                        if (!compile(u.input(), hoisted, extracted, slabSlots)) {
                            return false;
                        }
                        if (!compile(u.input(), hoisted, extracted, slabSlots)) {
                            return false;
                        }
                        if (!compile(u.input(), hoisted, extracted, slabSlots)) {
                            return false;
                        }
                        raw.write(OP_MUL);
                        raw.write(OP_MUL);
                        return true;
                    }
                    case SQUEEZE -> {
                        if (!compile(u.input(), hoisted, extracted, slabSlots)) {
                            return false;
                        }
                        raw.write(OP_SQUEEZE);
                        return true;
                    }
                    default -> {
                        if (!compile(u.input(), hoisted, extracted, slabSlots)) {
                            return false;
                        }
                        switch (u.op()) {
                            case ABS -> raw.write(OP_ABS);
                            case NEG -> raw.write(OP_NEG);
                            case SQUARE -> raw.write(OP_SQUARE);
                            case HALF_NEGATIVE -> {
                                raw.write(OP_COND_NEG_SCALE);
                                writeLeU16(addConst(0.5));
                            }
                            case QUARTER_NEGATIVE -> {
                                raw.write(OP_COND_NEG_SCALE);
                                writeLeU16(addConst(0.25));
                            }
                            default -> {
                                this.unsupportedClass = "UnaryOp." + u.op().name();
                                return false;
                            }
                        }
                        return true;
                    }
                }
            }
            if (node instanceof IRNode.Clamp cl) {
                if (!compile(cl.input(), hoisted, extracted, slabSlots)) {
                    return false;
                }
                emitConst(cl.max());
                raw.write(OP_MIN);
                emitConst(cl.min());
                raw.write(OP_MAX);
                return true;
            }
            this.unsupportedClass = node.getClass().getName();
            return false;
        }

        private boolean emitYClampedGradient(IRNode.YClampedGradient g) throws IOException {
            if (g.fromY() == g.toY()
                    || !Double.isFinite(g.fromValue())
                    || !Double.isFinite(g.toValue())) {
                return false;
            }
            raw.write(OP_Y_CLAMPED_GRADIENT);
            writeLeU16(addConst((double) g.fromY()));
            writeLeU16(addConst((double) g.toY()));
            writeLeU16(addConst(g.fromValue()));
            writeLeU16(addConst(g.toValue()));
            return true;
        }

        private boolean emitRangeChoice(IRNode.RangeChoice rc, IRNode hoisted, Set<IRNode> extracted,
                                        IdentityHashMap<IRNode, Integer> slabSlots) throws IOException {
            if (!Double.isFinite(rc.min()) || !Double.isFinite(rc.max()) || !(rc.min() < rc.max())) {
                return false;
            }
            if (!compile(rc.input(), hoisted, extracted, slabSlots)) {
                return false;
            }
            if (!compile(rc.whenInRange(), hoisted, extracted, slabSlots)) {
                return false;
            }
            if (!compile(rc.whenOutOfRange(), hoisted, extracted, slabSlots)) {
                return false;
            }
            raw.write(OP_RANGE_CHOICE);
            writeLeU16(addConst(rc.min()));
            writeLeU16(addConst(rc.max()));
            return true;
        }

        private boolean emitWeirdRarity(IRNode.WeirdRarity wr, IRNode hoisted, Set<IRNode> extracted,
                                        IdentityHashMap<IRNode, Integer> slabSlots) throws IOException {
            IRNode input = wr.input();
            if (wr.rarityValueMapperOrdinal() == 0) {
                return emitPiecewiseRangeChoice(input, hoisted, extracted, slabSlots,
                        new double[]{-Double.MAX_VALUE, -0.5D, -0.5D, 0.0D, 0.0D, 0.5D},
                        new double[]{0.75D, 1.0D, 1.5D, 2.0D});
            }
            return emitPiecewiseRangeChoice(input, hoisted, extracted, slabSlots,
                    new double[]{-Double.MAX_VALUE, -0.75D, -0.75D, -0.5D, -0.5D, 0.5D, 0.5D, 0.75D},
                    new double[]{0.5D, 0.75D, 1.0D, 2.0D, 3.0D});
        }

        /**
         * Emits nested range choices over the same input:
         * [b0,b1)->v0 else [b2,b3)->v1 else ... else vLast.
         * bounds contains 2 * (values.length - 1) entries.
         */
        private boolean emitPiecewiseRangeChoice(IRNode input, IRNode hoisted, Set<IRNode> extracted,
                                                 IdentityHashMap<IRNode, Integer> slabSlots,
                                                 double[] bounds, double[] values) throws IOException {
            int branches = values.length - 1;
            if (bounds.length != branches * 2) {
                return false;
            }
            return emitPiecewiseRangeChoiceArm(input, hoisted, extracted, slabSlots, bounds, values, 0);
        }

        private boolean emitPiecewiseRangeChoiceArm(IRNode input, IRNode hoisted, Set<IRNode> extracted,
                                                    IdentityHashMap<IRNode, Integer> slabSlots,
                                                    double[] bounds, double[] values, int arm) throws IOException {
            if (arm >= values.length - 1) {
                emitConst(values[values.length - 1]);
                return true;
            }
            double min = bounds[arm * 2];
            double max = bounds[arm * 2 + 1];
            if (!Double.isFinite(min) || !Double.isFinite(max) || !(min < max)) {
                return false;
            }
            if (!compile(input, hoisted, extracted, slabSlots)) {
                return false;
            }
            emitConst(values[arm]);
            if (!emitPiecewiseRangeChoiceArm(input, hoisted, extracted, slabSlots, bounds, values, arm + 1)) {
                return false;
            }
            raw.write(OP_RANGE_CHOICE);
            writeLeU16(addConst(min));
            writeLeU16(addConst(max));
            return true;
        }
    }
}
