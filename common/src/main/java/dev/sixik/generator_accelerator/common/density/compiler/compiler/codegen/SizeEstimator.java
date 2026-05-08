package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Approximate JVM bytecode size for an {@link IRNode} subtree, mirroring
 * {@link Codegen#emit(String, IRNode, dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount.Result, java.util.Set, ConstantPool, double, double)}.
 *
 * <p>The numbers don't need to be exact — they only need to track the relative
 * ordering of methods to drive {@link Splitter}'s extraction policy and to
 * detect {@link MethodTooLargeException}-bound methods before ASM does. The
 * cost of being a little high is over-extraction; the cost of being too low
 * is the splitter missing an oversize method and a {@link
 * org.objectweb.asm.MethodTooLargeException} bubbling up to {@link
 * dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler#compileWithDetail(net.minecraft.world.level.levelgen.DensityFunction)}
 * — at which point the affected DF falls back to vanilla. We therefore err on
 * the high side for nodes whose actual emit varies a lot (splines).
 *
 * <p>An extracted node always evaluates to the call-site size of an MH dispatch:
 * <pre>
 *   ALOAD this                 (1)
 *   GETFIELD helperHandles     (3)
 *   LDC idx                    (1-3)
 *   AALOAD                     (1)
 *   ALOAD this                 (1)
 *   ALOAD ctx                  (1)
 *   INVOKEVIRTUAL invokeExact  (3)
 *                            -----
 *                              ~14 bytes (round up to 16)
 * </pre>
 */
final class SizeEstimator {

    /** Approximate cost in bytes for one MethodHandle.invokeExact call site. */
    static final int CALL_SITE_BYTES = 16;

    private final IdentityHashMap<IRNode, Integer> sizes = new IdentityHashMap<>();
    private Set<IRNode> extracted = Set.of();
    private final ConstantPool pool;

    SizeEstimator() { this(null); }

    /**
     * @param pool optional pool used to size {@link IRNode.InlinedNoise} nodes accurately
     *             (per-octave count is encoded in the spec). Pass {@code null} when sizing
     *             before the noise expander has run; in that case the InlinedNoise branch
     *             will fall back to a conservative estimate.
     */
    SizeEstimator(ConstantPool pool) {
        this.pool = pool;
    }

    /** Drop the memo so a new {@code extracted} mask is recomputed properly. */
    void invalidate() {
        sizes.clear();
    }

    /**
     * Inclusive byte-cost of the subtree rooted at {@code node}, treating any
     * descendant in {@code extracted} as a 7-byte call site.
     */
    int size(IRNode node, Set<IRNode> extracted) {
        if (this.extracted != extracted) {
            this.extracted = extracted;
            sizes.clear();
        }
        return computeSize(node, true);
    }

    /**
     * Self-only byte cost for {@code node} (its own opcode footprint inside
     * its own helper if it were extracted) — children sized as call sites.
     */
    int selfSize(IRNode node, Set<IRNode> extracted) {
        if (this.extracted != extracted) {
            this.extracted = extracted;
            sizes.clear();
        }
        // Force "self" mode: extract every non-leaf child by passing an
        // override that treats children as already extracted.
        return rawSize(node, /* treatAllChildrenAsExtracted */ true);
    }

    private int computeSize(IRNode node, boolean isRoot) {
        if (!isRoot && extracted.contains(node)) {
            return CALL_SITE_BYTES;
        }
        Integer cached = sizes.get(node);
        if (cached != null) return cached;
        int s = rawSize(node, false);
        sizes.put(node, s);
        return s;
    }

    private int sizeOfChild(IRNode child) {
        return computeSize(child, false);
    }

    private int rawSize(IRNode node, boolean treatAllChildrenAsExtracted) {
        return switch (node) {
            case IRNode.Const c -> 3;
            case IRNode.BlockX bx -> 3;
            case IRNode.BlockY by -> 3;
            case IRNode.BlockZ bz -> 3;

            case IRNode.Bin bin -> {
                int self = switch (bin.op()) {
                    case ADD, SUB, MUL, DIV -> 1;
                    case MIN, MAX -> 4; // INVOKESTATIC Math.min/max
                };
                int l = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(bin.left());
                int r = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(bin.right());
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield l + r + self + spill;
            }
            case IRNode.Unary u -> {
                int self = switch (u.op()) {
                    case ABS -> 4;
                    case NEG -> 1;
                    case SQUARE -> 2;
                    case CUBE -> 3;
                    case HALF_NEGATIVE, QUARTER_NEGATIVE -> 18; // DUP2/DCONST_0/DCMPL/IFGT/LDC/DMUL/GOTO
                    case SQUEEZE -> 4;
                };
                int in = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(u.input());
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield in + self + spill;
            }
            case IRNode.Clamp cl -> {
                int in = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(cl.input());
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield in + 3 /*LDC*/ + 4 /*Math.min*/ + 3 /*LDC*/ + 4 /*Math.max*/ + spill;
            }
            case IRNode.RangeChoice rc -> {
                int in = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(rc.input());
                int wir = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(rc.whenInRange());
                int wor = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(rc.whenOutOfRange());
                int self = 30; // 2x DSTORE/DLOAD pair, 2x LDC+DCMP+IF, GOTO + label fixups
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield in + wir + wor + self + spill;
            }
            case IRNode.YClampedGradient g -> 26; // ILOAD/I2D + 4 LDC + INVOKESTATIC

            case IRNode.Noise n -> {
                int self = 50; // ALOAD/GETFIELD/AALOAD + 3x (ILOAD/I2D + LDC + DMUL) + INVOKEVIRTUAL
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield self + spill;
            }
            case IRNode.ShiftedNoise sn -> {
                int sx = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(sn.shiftX());
                int sy = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(sn.shiftY());
                int sz = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(sn.shiftZ());
                int self = 60; // 3x (ILOAD/I2D + LDC + DMUL + DADD) + GETFIELD/AALOAD + INVOKEVIRTUAL
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield sx + sy + sz + self + spill;
            }
            case IRNode.ShiftA sa -> 50;
            case IRNode.ShiftB sb -> 50;
            case IRNode.Shift s -> 50;
            case IRNode.WeirdScaled w -> {
                int in = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(w.input());
                int self = 70; // INVOKESTATIC weirdRarity + spill + 3x (ILOAD/I2D + DLOAD + DDIV) + GETFIELD/AALOAD + INVOKEVIRTUAL + abs + DMUL
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield in + self + spill;
            }
            case IRNode.InlinedNoise n -> {
                // Coord prep: 3 sub-trees + 3 DSTOREs (3 bytes each).
                int cx = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(n.coordX());
                int cy = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(n.coordY());
                int cz = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(n.coordZ());
                int coordPrep = cx + cy + cz + 9;

                int firstOctaves;
                int secondOctaves;
                boolean secondPreScaleNeeded;
                if (pool != null) {
                    var spec = pool.noiseSpec(n.specPoolIndex());
                    firstOctaves = spec.first().activeOctaves().length;
                    secondOctaves = spec.second().activeOctaves().length;
                    secondPreScaleNeeded = secondOctaves > 0
                            && Double.compare(spec.second().inputCoordScale(), 1.0) != 0;
                } else {
                    // Conservative pre-spec estimate: assume worst-case 8 active octaves
                    // per branch (vanilla noises top out around there) and assume the
                    // second branch always needs a pre-scale.
                    firstOctaves = 8;
                    secondOctaves = 8;
                    secondPreScaleNeeded = true;
                }

                // Per octave: LDC ampVF + ALOAD/GETFIELD + 3x (DLOAD + LDC + DMUL +
                // INVOKESTATIC wrap) + INVOKEVIRTUAL noise + DMUL + DADD ≈ 65 bytes.
                int perOctave = 65;
                // Second branch pre-scale (3x DLOAD/LDC/DMUL/DSTORE).
                int secondPreScale = secondPreScaleNeeded ? 30 : 0;
                // DCONST_0 fallback (1 byte) if a branch has zero octaves.
                int branchOverhead = (firstOctaves == 0 ? 1 : 0) + (secondOctaves == 0 ? 1 : 0);
                // Final valueFactor multiply: LDC + DMUL = 4 bytes.
                int valueFactor = 4;
                int self = (firstOctaves + secondOctaves) * perOctave + secondPreScale + branchOverhead + valueFactor;
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield coordPrep + self + spill;
            }
            case IRNode.InlinedBlendedNoise b -> {
                // Unrolled 8 + 16 + 16 octave sites, 5-arg noise, null-guards, Mth.clampedLerp.
                int self = 4500;
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield self + spill;
            }
            case IRNode.WeirdRarity wr -> {
                int in = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(wr.input());
                int self = 5; // ldcInt ordinal (1-3) + INVOKESTATIC weirdRarity (3)
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield in + self + spill;
            }

            case IRNode.Spline.Constant sc -> 3; // LDC
            case IRNode.Spline.Multipoint mp -> sizeOfMultipoint(mp, treatAllChildrenAsExtracted);

            case IRNode.Marker m -> 12; // ALOAD [CHECKCAST] GETFIELD ext_i + ALOAD/INVOKEINTERFACE
            case IRNode.Invoke iv -> 12;
            case IRNode.Beardifier b -> 12;
            case IRNode.EndIslands e -> 14;
            case IRNode.BlendDensity bd -> {
                int in = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(bd.input());
                int self = 25; // ALOAD/INVOKEINTERFACE getBlender + ALOAD + INVOKEVIRTUAL blendDensity
                int spill = isSpillCandidate(node) ? 4 : 0;
                yield in + self + spill;
            }
        };
    }

    private int sizeOfMultipoint(IRNode.Spline.Multipoint mp, boolean treatAllChildrenAsExtracted) {
        // Coordinate evaluation + initial FSTORE.
        int coordCost = treatAllChildrenAsExtracted ? CALL_SITE_BYTES : sizeOfChild(mp.coordinate());
        int prologue = coordCost + 8;       // D2F + FSTORE + bounds-check skeleton
        int dispatch = 25;                  // outer FCMPG/IFLT + FCMPL/IFGE for left/right ext
        int n = mp.locations().length;
        if (n == 1) {
            // Single point: just emit linear extension of the only sub-spline.
            return prologue + 6 + sizeOfSubSpline(mp.values().get(0)) + 12;
        }
        int perInterval = 6;                // FLOAD/LDC/FCMPG/IFGE + GOTO + label
        int interpolatedSegment = 110;      // worst-case per-segment cubic
        int valueCost = 0;
        for (IRNode.Spline v : mp.values()) {
            valueCost += sizeOfSubSpline(v);
        }
        int ladder = Codegen.useBinarySplineSearch(n)
                ? sizeOfBinarySplineDispatch(n, interpolatedSegment, valueCost)
                : sizeOfLinearSplineDispatch(n, perInterval, interpolatedSegment, valueCost);
        // Linear extensions on the left/right.
        int extensions = 2 * (sizeOfSubSpline(mp.values().get(0)) + 12);
        return prologue + dispatch + ladder + extensions;
    }

    private int sizeOfLinearSplineDispatch(int pointCount, int perInterval, int interpolatedSegment, int valueCost) {
        // Each interval pays for its own (l0,l1) ladder branch + the cubic
        // body, plus values for both endpoints. Endpoint values are shared
        // between adjacent intervals in the inlined emitter, but to stay
        // pessimistic we attribute the value cost to the interval pair.
        return (pointCount - 1) * (perInterval + interpolatedSegment) + 2 * valueCost;
    }

    private int sizeOfBinarySplineDispatch(int pointCount, int interpolatedSegment, int valueCost) {
        int segmentCount = pointCount - 1;
        int internalNodes = Math.max(0, segmentCount - 1);
        int comparisonNode = 8; // FLOAD/LDC/FCMPG/IFGE + branch label
        int leafJump = 3;       // GOTO end after each emitted segment
        return internalNodes * comparisonNode
                + segmentCount * (interpolatedSegment + leafJump)
                + 2 * valueCost;
    }

    private int sizeOfSubSpline(IRNode.Spline s) {
        // Sub-splines are evaluated through the same general emitter (with a
        // following D2F if the inner is itself a multipoint). When the splitter
        // marks the sub-spline as extracted, the evaluator collapses to a
        // single call site, so honour the same rule here.
        return computeSize(s, false);
    }

    private boolean isSpillCandidate(IRNode node) {
        // Mirror EmitState.isSpillCandidate for the (small) DUP2+DSTORE bytes,
        // but we don't have refcounts here — assume a conservative no-spill
        // until the real estimate is wired in. Bin/Unary are cheap enough that
        // overestimating spill cost a few bytes is fine.
        return false;
    }
}
