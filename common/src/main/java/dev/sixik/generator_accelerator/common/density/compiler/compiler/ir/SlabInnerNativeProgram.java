package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

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

    public record Result(byte[] bytecode, double[] constants) {}

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
            };
            slabSlots.put(key, s.slotIndex());
        }
        var b = new Builder();
        try {
            if (!b.compile(root, plan.hoistedSubtree(), extracted, slabSlots)) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        byte[] bytecode = b.bytes();
        if (!isValidStackProgram(bytecode, b.constCount())) {
            return Optional.empty();
        }
        return Optional.of(new Result(bytecode, b.consts()));
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

        boolean compile(IRNode node, IRNode hoisted, Set<IRNode> extracted,
                        IdentityHashMap<IRNode, Integer> slabSlots) throws IOException {
            if (node == hoisted) {
                raw.write(OP_HOIST);
                return true;
            }
            if (extracted.contains(node)) {
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
    }
}
