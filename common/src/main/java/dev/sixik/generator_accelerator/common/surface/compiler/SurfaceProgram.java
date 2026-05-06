package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;

public final class SurfaceProgram {
    private static final int OP_RULE = 0;
    private static final int OP_BLOCK = 1;
    private static final int OP_TEST_BLOCK = 2;

    private final int[] opcodes;
    private final int[] intOperands;
    private final Object[] objectOperands;
    private final int requirements;
    private final int fallbackIslandCount;
    private final boolean mayWriteFluid;

    SurfaceProgram(SurfaceRuleNode root, int fallbackIslandCount) {
        this.requirements = root.requirements();
        this.fallbackIslandCount = fallbackIslandCount;
        this.mayWriteFluid = root.mayWriteFluid();

        SurfaceRuleNode[] rules = root instanceof SequenceSurfaceRuleNode sequenceRule ? sequenceRule.rules() : new SurfaceRuleNode[]{root};
        if (rules.length == 1 && rules[0] == EmptySurfaceRuleNode.INSTANCE) {
            this.opcodes = new int[0];
            this.intOperands = new int[0];
            this.objectOperands = new Object[0];
        } else {
            this.opcodes = new int[rules.length];
            this.intOperands = new int[rules.length];
            this.objectOperands = new Object[rules.length];
            for (int i = 0; i < rules.length; i++) {
                encodeRule(i, rules[i]);
            }
        }
    }

    public void apply(int[] rawBlockData, Mask4096 stoneMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        scratch.beginSection();
        scratch.activeMask.copyFrom(stoneMask);

        Mask4096 activeMask = scratch.activeMask;
        int[] localOpcodes = this.opcodes;
        int[] localIntOperands = this.intOperands;
        Object[] localObjectOperands = this.objectOperands;

        for (int i = 0; i < localOpcodes.length; i++) {
            if (activeMask.isEmpty()) {
                SurfaceMetrics.activeMaskEarlyExit();
                return;
            }

            switch (localOpcodes[i]) {
                case OP_BLOCK -> {
                    activeMask.applyBlockState(rawBlockData, localIntOperands[i]);
                    activeMask.clear();
                    return;
                }
                case OP_TEST_BLOCK -> applyTestBlock(rawBlockData, activeMask, ctx, scratch, (SurfaceConditionNode) localObjectOperands[i], localIntOperands[i]);
                case OP_RULE -> ((SurfaceRuleNode) localObjectOperands[i]).apply(rawBlockData, activeMask, ctx, scratch);
                default -> throw new IllegalStateException("Unknown surface opcode: " + localOpcodes[i]);
            }
        }
    }

    public int requirements() {
        return this.requirements;
    }

    public int fallbackIslandCount() {
        return this.fallbackIslandCount;
    }

    public boolean requires(int requirementMask) {
        return (this.requirements & requirementMask) != 0;
    }

    public boolean mayWriteFluid() {
        return this.mayWriteFluid;
    }

    private void encodeRule(int index, SurfaceRuleNode rule) {
        if (rule instanceof BlockSurfaceRuleNode blockRule) {
            this.opcodes[index] = OP_BLOCK;
            this.intOperands[index] = blockRule.blockId();
            return;
        }

        if (rule instanceof TestBlockSurfaceRuleNode testBlockRule) {
            this.opcodes[index] = OP_TEST_BLOCK;
            this.intOperands[index] = testBlockRule.blockId();
            this.objectOperands[index] = testBlockRule.condition();
            return;
        }

        this.opcodes[index] = OP_RULE;
        this.objectOperands[index] = rule;
    }

    private static void applyTestBlock(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch, SurfaceConditionNode condition, int blockId) {
        int mark = scratch.mark();
        Mask4096 matchingMask = scratch.pushMask();
        matchingMask.copyFrom(activeMask);
        condition.filter(matchingMask, ctx, scratch);

        if (!matchingMask.isEmpty()) {
            matchingMask.applyBlockState(rawBlockData, blockId);
            activeMask.andNot(matchingMask);
        }

        scratch.restore(mark);
    }
}
