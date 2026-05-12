package dev.sixik.generator_accelerator.common.beardifier.mixin;

import com.google.common.collect.Iterators;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.*;

@Mixin(Beardifier.class)
public abstract class MixinBeardifier implements DensityFunctions.BeardifierOrMarker, DfcCellFillAccess {

    @Shadow
    @Final
    private ObjectListIterator<Beardifier.Rigid> pieceIterator;
    @Shadow
    @Final
    private ObjectListIterator<JigsawJunction> junctionIterator;

    @Shadow
    private static double getBuryContribution(double d, double e, double f) {
        throw new RuntimeException();
    }

    @Shadow
    @Final
    private static float[] BEARD_KERNEL;

    @Unique private static final int GA$TERRAIN_NONE = TerrainAdjustment.NONE.ordinal();
    @Unique private static final int GA$TERRAIN_BURY = TerrainAdjustment.BURY.ordinal();
    @Unique private static final int GA$TERRAIN_BEARD_THIN = TerrainAdjustment.BEARD_THIN.ordinal();
    @Unique private static final int GA$TERRAIN_BEARD_BOX = TerrainAdjustment.BEARD_BOX.ordinal();
    @Unique private static final int GA$TERRAIN_ENCAPSULATE = TerrainAdjustment.ENCAPSULATE.ordinal();
    @Unique private static volatile float[] GA$BEARD_SAME_Y;

    private int[] c2me$pieceMinX;
    private int[] c2me$pieceMaxX;
    private int[] c2me$pieceMinY;
    private int[] c2me$pieceMaxY;
    private int[] c2me$pieceMinZ;
    private int[] c2me$pieceMaxZ;
    private int[] c2me$pieceGroundY;
    private int[] c2me$pieceInfluenceMinX;
    private int[] c2me$pieceInfluenceMaxX;
    private int[] c2me$pieceInfluenceMinY;
    private int[] c2me$pieceInfluenceMaxY;
    private int[] c2me$pieceInfluenceMinZ;
    private int[] c2me$pieceInfluenceMaxZ;
    private byte[] c2me$pieceTerrain;
    private int[] c2me$junctionX;
    private int[] c2me$junctionY;
    private int[] c2me$junctionZ;
    private int ga$influenceMinX;
    private int ga$influenceMaxX;
    private int ga$influenceMinY;
    private int ga$influenceMaxY;
    private int ga$influenceMinZ;
    private int ga$influenceMaxZ;
    private boolean ga$hasInfluence;

    @Unique
    private void c2me$initArrays() {
        Beardifier.Rigid[] pieces = Iterators.toArray(this.pieceIterator, Beardifier.Rigid.class);
        this.pieceIterator.back(Integer.MAX_VALUE);
        this.ga$hasInfluence = false;
        this.ga$influenceMinX = Integer.MAX_VALUE;
        this.ga$influenceMinY = Integer.MAX_VALUE;
        this.ga$influenceMinZ = Integer.MAX_VALUE;
        this.ga$influenceMaxX = Integer.MIN_VALUE;
        this.ga$influenceMaxY = Integer.MIN_VALUE;
        this.ga$influenceMaxZ = Integer.MIN_VALUE;
        this.c2me$pieceMinX = new int[pieces.length];
        this.c2me$pieceMaxX = new int[pieces.length];
        this.c2me$pieceMinY = new int[pieces.length];
        this.c2me$pieceMaxY = new int[pieces.length];
        this.c2me$pieceMinZ = new int[pieces.length];
        this.c2me$pieceMaxZ = new int[pieces.length];
        this.c2me$pieceGroundY = new int[pieces.length];
        this.c2me$pieceInfluenceMinX = new int[pieces.length];
        this.c2me$pieceInfluenceMaxX = new int[pieces.length];
        this.c2me$pieceInfluenceMinY = new int[pieces.length];
        this.c2me$pieceInfluenceMaxY = new int[pieces.length];
        this.c2me$pieceInfluenceMinZ = new int[pieces.length];
        this.c2me$pieceInfluenceMaxZ = new int[pieces.length];
        this.c2me$pieceTerrain = new byte[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            Beardifier.Rigid piece = pieces[i];
            BoundingBox box = piece.box();
            int terrainKind = piece.terrainAdjustment().ordinal();
            int minX = box.minX();
            int maxX = box.maxX();
            int minY = box.minY();
            int maxY = box.maxY();
            int minZ = box.minZ();
            int maxZ = box.maxZ();
            int groundY = minY + piece.groundLevelDelta();
            this.c2me$pieceMinX[i] = box.minX();
            this.c2me$pieceMaxX[i] = box.maxX();
            this.c2me$pieceMinY[i] = box.minY();
            this.c2me$pieceMaxY[i] = box.maxY();
            this.c2me$pieceMinZ[i] = box.minZ();
            this.c2me$pieceMaxZ[i] = box.maxZ();
            this.c2me$pieceGroundY[i] = groundY;
            this.c2me$pieceTerrain[i] = (byte) terrainKind;
            this.ga$initPieceInfluenceBounds(i, terrainKind, minX, maxX, minY, maxY, minZ, maxZ, groundY);
        }

        JigsawJunction[] junctions = Iterators.toArray(this.junctionIterator, JigsawJunction.class);
        this.junctionIterator.back(Integer.MAX_VALUE);
        this.c2me$junctionX = new int[junctions.length];
        this.c2me$junctionY = new int[junctions.length];
        this.c2me$junctionZ = new int[junctions.length];
        for (int i = 0; i < junctions.length; i++) {
            JigsawJunction junction = junctions[i];
            this.c2me$junctionX[i] = junction.getSourceX();
            this.c2me$junctionY[i] = junction.getSourceGroundY();
            this.c2me$junctionZ[i] = junction.getSourceZ();
            this.ga$mergeInfluenceBounds(
                    this.c2me$junctionX[i] - 12,
                    this.c2me$junctionX[i] + 11,
                    this.c2me$junctionY[i] - 12,
                    this.c2me$junctionY[i] + 11,
                    this.c2me$junctionZ[i] - 12,
                    this.c2me$junctionZ[i] + 11
            );
        }
    }

    @Unique
    private double c2me$computeAt(int i, int j, int k) {
        if (this.c2me$pieceTerrain == null || this.c2me$junctionX == null) {
            this.c2me$initArrays();
        }
        if (!this.ga$hasInfluence
                || i < this.ga$influenceMinX || i > this.ga$influenceMaxX
                || j < this.ga$influenceMinY || j > this.ga$influenceMaxY
                || k < this.ga$influenceMinZ || k > this.ga$influenceMaxZ) {
            return 0.0D;
        }

        double d = 0.0;

        int[] minX = this.c2me$pieceMinX;
        int[] maxX = this.c2me$pieceMaxX;
        int[] minY = this.c2me$pieceMinY;
        int[] maxY = this.c2me$pieceMaxY;
        int[] minZ = this.c2me$pieceMinZ;
        int[] maxZ = this.c2me$pieceMaxZ;
        int[] groundY = this.c2me$pieceGroundY;
        int[] influenceMinX = this.c2me$pieceInfluenceMinX;
        int[] influenceMaxX = this.c2me$pieceInfluenceMaxX;
        int[] influenceMinY = this.c2me$pieceInfluenceMinY;
        int[] influenceMaxY = this.c2me$pieceInfluenceMaxY;
        int[] influenceMinZ = this.c2me$pieceInfluenceMinZ;
        int[] influenceMaxZ = this.c2me$pieceInfluenceMaxZ;
        byte[] terrain = this.c2me$pieceTerrain;
        for (int i1 = 0; i1 < terrain.length; i1++) {
            int terrainKind = terrain[i1] & 0xFF;
            if (terrainKind == GA$TERRAIN_NONE) {
                continue;
            }
            if (i < influenceMinX[i1] || i > influenceMaxX[i1]
                    || j < influenceMinY[i1] || j > influenceMaxY[i1]
                    || k < influenceMinZ[i1] || k > influenceMaxZ[i1]) {
                continue;
            }
            final int pieceMinX = minX[i1];
            final int pieceMaxX = maxX[i1];
            final int pieceMinZ = minZ[i1];
            final int pieceMaxZ = maxZ[i1];

            if (terrainKind == GA$TERRAIN_BURY) {
                final int m = Math.max(0, Math.max(pieceMinX - i, i - pieceMaxX));
                final int n = Math.max(0, Math.max(pieceMinZ - k, k - pieceMaxZ));
                final int p = j - groundY[i1];
                d += ga$getBuryContributionFast(m, (double) p / 2.0D, n);
            } else if (terrainKind == GA$TERRAIN_BEARD_THIN) {
                final int p = j - groundY[i1];
                final int m = Math.max(0, Math.max(pieceMinX - i, i - pieceMaxX));
                final int n = Math.max(0, Math.max(pieceMinZ - k, k - pieceMaxZ));
                d += ga$getBeardContributionSameY(m, p, n) * 0.8D;
            } else if (terrainKind == GA$TERRAIN_BEARD_BOX) {
                final int o = groundY[i1];
                final int pieceMaxY = maxY[i1];
                final int m = Math.max(0, Math.max(pieceMinX - i, i - pieceMaxX));
                final int n = Math.max(0, Math.max(pieceMinZ - k, k - pieceMaxZ));
                final int p = j - o;
                int yDistance = Math.max(0, Math.max(o - j, j - pieceMaxY));
                d += ga$getBeardContributionUnchecked(m, yDistance, n, p) * 0.8D;
            } else if (terrainKind == GA$TERRAIN_ENCAPSULATE) {
                final int pieceMinY = minY[i1];
                final int pieceMaxY = maxY[i1];
                final int m = Math.max(0, Math.max(pieceMinX - i, i - pieceMaxX));
                final int n = Math.max(0, Math.max(pieceMinZ - k, k - pieceMaxZ));
                int yDistance = Math.max(0, Math.max(pieceMinY - j, j - pieceMaxY));
                d += ga$getBuryContributionFast(
                        (double) m / 2.0D,
                        (double) yDistance / 2.0D,
                        (double) n / 2.0D
                ) * 0.8D;
            }
        }

        int[] junctionX = this.c2me$junctionX;
        int[] junctionY = this.c2me$junctionY;
        int[] junctionZ = this.c2me$junctionZ;
        for (int i1 = 0; i1 < junctionX.length; i1++) {
            final int r = i - junctionX[i1];
            final int l = j - junctionY[i1];
            final int m = k - junctionZ[i1];
            if (ga$inKernelRange(r) && ga$inKernelRange(l) && ga$inKernelRange(m)) {
                d += ga$getBeardContributionSameY(r, l, m) * 0.4D;
            }
        }

        return d;
    }

    @Unique
    private void ga$initPieceInfluenceBounds(
            int index,
            int terrainKind,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            int groundY
    ) {
        if (terrainKind == GA$TERRAIN_BURY) {
            this.ga$setPieceInfluenceBounds(index, minX - 5, maxX + 5, groundY - 11, groundY + 11, minZ - 5, maxZ + 5);
        } else if (terrainKind == GA$TERRAIN_BEARD_THIN) {
            this.ga$setPieceInfluenceBounds(index, minX - 11, maxX + 11, groundY - 12, groundY + 11, minZ - 11, maxZ + 11);
        } else if (terrainKind == GA$TERRAIN_BEARD_BOX) {
            this.ga$setPieceInfluenceBounds(index, minX - 11, maxX + 11, groundY - 11, maxY + 11, minZ - 11, maxZ + 11);
        } else if (terrainKind == GA$TERRAIN_ENCAPSULATE) {
            this.ga$setPieceInfluenceBounds(index, minX - 11, maxX + 11, minY - 11, maxY + 11, minZ - 11, maxZ + 11);
        }
    }

    @Unique
    private void ga$setPieceInfluenceBounds(int index, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        this.c2me$pieceInfluenceMinX[index] = minX;
        this.c2me$pieceInfluenceMaxX[index] = maxX;
        this.c2me$pieceInfluenceMinY[index] = minY;
        this.c2me$pieceInfluenceMaxY[index] = maxY;
        this.c2me$pieceInfluenceMinZ[index] = minZ;
        this.c2me$pieceInfluenceMaxZ[index] = maxZ;
        this.ga$mergeInfluenceBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    @Unique
    private void ga$mergeInfluenceBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        if (!this.ga$hasInfluence) {
            this.ga$influenceMinX = minX;
            this.ga$influenceMaxX = maxX;
            this.ga$influenceMinY = minY;
            this.ga$influenceMaxY = maxY;
            this.ga$influenceMinZ = minZ;
            this.ga$influenceMaxZ = maxZ;
            this.ga$hasInfluence = true;
            return;
        }
        if (minX < this.ga$influenceMinX) this.ga$influenceMinX = minX;
        if (maxX > this.ga$influenceMaxX) this.ga$influenceMaxX = maxX;
        if (minY < this.ga$influenceMinY) this.ga$influenceMinY = minY;
        if (maxY > this.ga$influenceMaxY) this.ga$influenceMaxY = maxY;
        if (minZ < this.ga$influenceMinZ) this.ga$influenceMinZ = minZ;
        if (maxZ > this.ga$influenceMaxZ) this.ga$influenceMaxZ = maxZ;
    }

    @Unique
    private static boolean ga$inKernelRange(int value) {
        return value >= -12 && value < 12;
    }

    @Unique
    private static double ga$getBeardContributionUnchecked(int i, int j, int k, int l) {
        double y = l + 0.5D;
        double lengthSquared = (double) i * (double) i + y * y + (double) k * (double) k;
        double contribution = -y * Mth.fastInvSqrt(lengthSquared / 2.0D) / 2.0D;
        int index = ((k + 12) * 24 + (i + 12)) * 24 + (j + 12);
        return contribution * (double) BEARD_KERNEL[index];
    }

    @Unique
    private static double ga$getBeardContributionSameY(int i, int j, int k) {
        float[] table = GA$BEARD_SAME_Y;
        if (table == null) {
            table = ga$initBeardSameYTable();
        }
        int index = ((k + 12) * 24 + (i + 12)) * 24 + (j + 12);
        return table[index];
    }

    @Unique
    private static float[] ga$initBeardSameYTable() {
        synchronized (Beardifier.class) {
            float[] table = GA$BEARD_SAME_Y;
            if (table != null) {
                return table;
            }
            table = new float[24 * 24 * 24];
            for (int k = -12; k < 12; k++) {
                for (int i = -12; i < 12; i++) {
                    for (int j = -12; j < 12; j++) {
                        double y = j + 0.5D;
                        double lengthSquared = (double) i * (double) i + y * y + (double) k * (double) k;
                        double contribution = -y * Mth.fastInvSqrt(lengthSquared / 2.0D) / 2.0D;
                        int index = ((k + 12) * 24 + (i + 12)) * 24 + (j + 12);
                        table[index] = (float) (contribution * (double) BEARD_KERNEL[index]);
                    }
                }
            }
            GA$BEARD_SAME_Y = table;
            return table;
        }
    }

    @Unique
    private static double ga$getBuryContributionFast(double x, double y, double z) {
        final double distanceSquared = x * x + y * y + z * z;
        if (distanceSquared > 36.0D) {
            return 0.0D;
        }
        return 1.0D - Math.sqrt(distanceSquared) / 6.0D;
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public double compute(FunctionContext context) {
        return this.c2me$computeAt(context.blockX(), context.blockY(), context.blockZ());
    }

    @Override
    public void dfc$fillCell(double[] out, NoiseChunk chunk) {
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        int idx = 0;
        chunk.arrayIndex = 0;
        for (int inCellX = 0; inCellX < cellW; inCellX++) {
            chunk.inCellX = inCellX;
            for (int inCellZ = 0; inCellZ < cellW; inCellZ++) {
                chunk.inCellZ = inCellZ;
                for (int inCellY = cellH - 1; inCellY >= 0; inCellY--) {
                    chunk.inCellY = inCellY;
                    chunk.arrayIndex = idx;
                    out[idx] = this.c2me$computeAt(chunk.blockX(), chunk.blockY(), chunk.blockZ());
                    idx++;
                }
            }
        }
        chunk.arrayIndex = idx;
    }

    @Override
    public void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        int idx = 0;
        chunk.arrayIndex = 0;
        for (int inCellX = 0; inCellX < cellW; inCellX++) {
            chunk.inCellX = inCellX;
            for (int inCellZ = 0; inCellZ < cellW; inCellZ++) {
                chunk.inCellZ = inCellZ;
                for (int inCellY = cellH - 1; inCellY >= 0; inCellY--) {
                    chunk.inCellY = inCellY;
                    chunk.arrayIndex = idx;
                    out[idx] += this.c2me$computeAt(chunk.blockX(), chunk.blockY(), chunk.blockZ());
                    idx++;
                }
            }
        }
        chunk.arrayIndex = idx;
    }

    /**
     * @author
     * @reason
     */
    @WrapMethod(method = "getBuryContribution")
    private static double bts$getBuryContribution(double x, double y, double z, Operation<Double> original) {
        return ga$getBuryContributionFast(x, y, z);
    }
}
