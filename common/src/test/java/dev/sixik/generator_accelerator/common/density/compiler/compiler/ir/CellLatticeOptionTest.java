package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellLatticeOptionTest {

    @Test
    void choosesLargerXzCandidateOverSmallerYCandidate() {
        IRNode yOnly = new IRNode.Clamp(
                new IRNode.Bin(
                        IRNode.BinOp.ADD,
                        new IRNode.Bin(IRNode.BinOp.MUL, IRNode.BlockY.INSTANCE, new IRNode.Const(0.5)),
                        new IRNode.YClampedGradient(-64, 320, -1.0, 1.0)
                ),
                -1.0,
                1.0
        );

        IRNode xzOnly = new IRNode.Clamp(
                new IRNode.Bin(
                        IRNode.BinOp.ADD,
                        new IRNode.Bin(IRNode.BinOp.MUL, IRNode.BlockX.INSTANCE, new IRNode.Const(0.25)),
                        new IRNode.Bin(
                                IRNode.BinOp.ADD,
                                new IRNode.Bin(IRNode.BinOp.MUL, IRNode.BlockZ.INSTANCE, new IRNode.Const(0.5)),
                                new IRNode.Bin(IRNode.BinOp.MUL, IRNode.BlockX.INSTANCE, IRNode.BlockZ.INSTANCE)
                        )
                ),
                -2.0,
                2.0
        );

        IRNode root = new IRNode.Bin(IRNode.BinOp.ADD, yOnly, xzOnly);

        Optional<CellLatticeOption.LatticePlan> plan = CellLatticeOption.analyze(root);
        assertTrue(plan.isPresent(), "Expected a lattice plan for mixed axis-only tree");
        assertEquals(CellLatticeOption.Axis.XZ_ONLY, plan.get().hoistAxis());
        assertEquals(xzOnly, plan.get().hoistedSubtree());
    }
}
