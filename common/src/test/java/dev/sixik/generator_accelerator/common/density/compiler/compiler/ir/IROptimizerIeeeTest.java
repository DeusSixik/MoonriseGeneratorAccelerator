package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

final class IROptimizerIeeeTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final ConstantPool pool = new ConstantPool();
    private final IRBuilder builder = new IRBuilder(pool, null);

    @Test
    void addPositiveZeroDoesNotFoldWhenInputMayBeSignedZero() {
        IRNode root = bin(IRNode.BinOp.ADD, blockX(), c(0.0));

        IRNode optimized = optimize(root);

        IRNode.Bin kept = assertInstanceOf(IRNode.Bin.class, optimized);
        assertEquals(IRNode.BinOp.ADD, kept.op());
    }

    @Test
    void addPositiveZeroStillFoldsWhenInputCannotBeZero() {
        IRNode positive = builder.intern(new IRNode.Clamp(blockX(), 1.0, 2.0));
        IRNode root = bin(IRNode.BinOp.ADD, positive, c(0.0));

        IRNode optimized = optimize(root);

        assertSame(positive, optimized);
    }

    @Test
    void multiplyByPositiveZeroDoesNotFoldWhenInputMayBeNegativeOrZero() {
        IRNode root = bin(IRNode.BinOp.MUL, blockX(), c(0.0));

        IRNode optimized = optimize(root);

        IRNode.Bin kept = assertInstanceOf(IRNode.Bin.class, optimized);
        assertEquals(IRNode.BinOp.MUL, kept.op());
    }

    @Test
    void minPositiveInfinityDoesNotFoldWhenInputMayBecomeNonFinite() {
        IRNode nonFinite = bin(IRNode.BinOp.DIV, blockX(), c(0.0));
        IRNode root = bin(IRNode.BinOp.MIN, c(Double.POSITIVE_INFINITY), nonFinite);

        IRNode optimized = optimize(root);

        IRNode.Bin kept = assertInstanceOf(IRNode.Bin.class, optimized);
        assertEquals(IRNode.BinOp.MIN, kept.op());
    }

    @Test
    void absSquareIsNotCollapsedBecauseNaNSignBitsAreObservable() {
        IRNode square = builder.intern(new IRNode.Unary(IRNode.UnaryOp.SQUARE, blockX()));
        IRNode root = builder.intern(new IRNode.Unary(IRNode.UnaryOp.ABS, square));

        IRNode optimized = optimize(root);

        IRNode.Unary kept = assertInstanceOf(IRNode.Unary.class, optimized);
        assertEquals(IRNode.UnaryOp.ABS, kept.op());
    }

    private IRNode optimize(IRNode root) {
        return IROptimizer.optimize(root, builder, pool).root();
    }

    private IRNode c(double value) {
        return builder.intern(new IRNode.Const(value));
    }

    private IRNode blockX() {
        return builder.intern(IRNode.BlockX.INSTANCE);
    }

    private IRNode bin(IRNode.BinOp op, IRNode left, IRNode right) {
        return builder.intern(new IRNode.Bin(op, left, right));
    }
}
