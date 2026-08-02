package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BoundsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void xAndZCoordinatesAreUnboundedButYRemainsFinite() {
        assertInterval(IRNode.BlockX.INSTANCE, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertInterval(IRNode.BlockY.INSTANCE, DimensionType.MIN_Y * 2.0, DimensionType.MAX_Y * 2.0);
        assertInterval(IRNode.BlockZ.INSTANCE, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    @Test
    void farPositiveXRangeChoiceIsNotFoldedOutByVerticalBounds() {
        assertRangeChoiceSurvives(IRNode.BlockX.INSTANCE, 1_000_000.0, 1_000_016.0);
    }

    @Test
    void farNegativeZRangeChoiceIsNotFoldedOutByVerticalBounds() {
        assertRangeChoiceSurvives(IRNode.BlockZ.INSTANCE, -1_000_016.0, -1_000_000.0);
    }

    @Test
    void farYRangeChoiceStillFoldsOutBecauseYIsVerticallyBounded() {
        ConstantPool pool = new ConstantPool();
        IRBuilder builder = new IRBuilder(pool, null);
        IRNode outOfRange = newRangeChoice(builder, IRNode.BlockY.INSTANCE, 1_000_000.0, 1_000_016.0);
        IROptimizer.Result result = IROptimizer.optimize(outOfRange, builder, pool);

        IRNode.Const folded = assertInstanceOf(IRNode.Const.class, result.root());
        assertEquals(-1.0, folded.value());
    }

    private static void assertInterval(IRNode node, double expectedMin, double expectedMax) {
        double[] interval = Bounds.interval(node, new ConstantPool());
        assertEquals(expectedMin, interval[0]);
        assertEquals(expectedMax, interval[1]);
    }

    private static void assertRangeChoiceSurvives(IRNode input, double min, double max) {
        ConstantPool pool = new ConstantPool();
        IRBuilder builder = new IRBuilder(pool, null);
        IRNode rangeChoice = newRangeChoice(builder, input, min, max);
        IROptimizer.Result result = IROptimizer.optimize(rangeChoice, builder, pool);

        IRNode.RangeChoice kept = assertInstanceOf(IRNode.RangeChoice.class, result.root());
        assertEquals(min, kept.min());
        assertEquals(max, kept.max());
    }

    private static IRNode newRangeChoice(IRBuilder builder, IRNode input, double min, double max) {
        return builder.intern(new IRNode.RangeChoice(
                builder.intern(input),
                min,
                max,
                builder.intern(new IRNode.Const(1.0)),
                builder.intern(new IRNode.Const(-1.0))));
    }
}