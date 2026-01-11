package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import com.google.common.collect.Iterators;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.*;

@Mixin(Beardifier.class)
public abstract class MixinBeardifier implements DensityFunctions.BeardifierOrMarker {

    @Shadow
    @Final
    private ObjectListIterator<Beardifier.Rigid> pieceIterator;
    @Shadow
    @Final
    private ObjectListIterator<JigsawJunction> junctionIterator;

    @Shadow
    private static double getBeardContribution(int i, int j, int k, int l) {
        throw new RuntimeException();
    }

    @Shadow
    private static double getBuryContribution(double d, double e, double f) {
        throw new RuntimeException();
    }

    private Beardifier.Rigid[] c2me$pieceArray;
    private JigsawJunction[] c2me$junctionArray;

    @Unique
    private void c2me$initArrays() {
        this.c2me$pieceArray = Iterators.toArray(this.pieceIterator, Beardifier.Rigid.class);
        this.pieceIterator.back(Integer.MAX_VALUE);
        this.c2me$junctionArray = Iterators.toArray(this.junctionIterator, JigsawJunction.class);
        this.junctionIterator.back(Integer.MAX_VALUE);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext context) {
        if (this.c2me$pieceArray == null || this.c2me$junctionArray == null) {
            this.c2me$initArrays();
        }

        final int i = context.blockX();
        final int j = context.blockY();
        final int k = context.blockZ();
        double d = 0.0;

        for (Beardifier.Rigid piece : this.c2me$pieceArray) {
            final BoundingBox blockBox = piece.box();
            final int l = piece.groundLevelDelta();
            final int m = Math.max(0, Math.max(blockBox.minX() - i, i - blockBox.maxX()));
            final int n = Math.max(0, Math.max(blockBox.minZ() - k, k - blockBox.maxZ()));
            final int o = blockBox.minY() + l;
            final int p = j - o;

            d += switch (piece.terrainAdjustment()) { // 2 switch statement merged
                case NONE -> 0.0;
                case BURY -> getBuryContribution(m, (double) p / 2.0, n);
                case BEARD_THIN -> getBeardContribution(m, p, n, p) * 0.8;
                case BEARD_BOX ->
                        getBeardContribution(m, Math.max(0, Math.max(o - j, j - blockBox.maxY())), n, p) * 0.8;
                case ENCAPSULATE ->
                        getBuryContribution((double) m / 2.0, (double) Math.max(0, Math.max(blockBox.minY() - j, j - blockBox.maxY())) / 2.0, (double) n / 2.0) * 0.8;
            };
        }

        for (JigsawJunction jigsawJunction : this.c2me$junctionArray) {
            final int r = i - jigsawJunction.getSourceX();
            final int l = j - jigsawJunction.getSourceGroundY();
            final int m = k - jigsawJunction.getSourceZ();
            d += getBeardContribution(r, l, m, l) * 0.4;
        }

        return d;
    }

    /**
     * @author
     * @reason
     */
    @WrapMethod(method = "getBuryContribution")
    private static double bts$getBuryContribution(double x, double y, double z, Operation<Double> original) {
        final double d = Math.sqrt(x * x + y * y + z * z);
        if (d > 6.0) {
            return 0.0;
        } else {
            return 1.0 - d / 6.0;
        }
    }
}
