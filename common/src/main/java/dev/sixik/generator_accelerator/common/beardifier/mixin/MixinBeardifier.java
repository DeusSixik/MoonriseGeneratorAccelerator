package dev.sixik.generator_accelerator.common.beardifier.mixin;

import com.google.common.collect.Iterators;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sixik.generator_accelerator.common.beardifier.*;
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

import java.util.Arrays;

@Mixin(Beardifier.class)
public abstract class MixinBeardifier implements DensityFunctions.BeardifierOrMarker, DfcCellFillAccess {

    @Shadow
    @Final
    private ObjectListIterator<Beardifier.Rigid> pieceIterator;
    @Shadow
    @Final
    private ObjectListIterator<JigsawJunction> junctionIterator;

    @Shadow
    @Final
    private static float[] BEARD_KERNEL;

    @Unique private static final int GA$TERRAIN_NONE = TerrainAdjustment.NONE.ordinal();
    @Unique private static final int GA$TERRAIN_BURY = TerrainAdjustment.BURY.ordinal();
    @Unique private static final int GA$TERRAIN_BEARD_THIN = TerrainAdjustment.BEARD_THIN.ordinal();
    @Unique private static final int GA$TERRAIN_BEARD_BOX = TerrainAdjustment.BEARD_BOX.ordinal();
    @Unique private static final int GA$TERRAIN_ENCAPSULATE = TerrainAdjustment.ENCAPSULATE.ordinal();
    @Unique private static final int GA$BURY_RADIUS = 5;
    @Unique private static final int GA$BEARD_RADIUS = 11;
    @Unique private static volatile float[] GA$BEARD_SAME_Y;
    @Unique private static final ThreadLocal<GABeardifierCellScratch> GA$CELL_SCRATCH =
            ThreadLocal.withInitial(GABeardifierCellScratch::new);

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
    private int ga$pieceBuryCount;
    private int ga$pieceThinCount;
    private int ga$pieceBoxCount;
    private int ga$pieceEncapsulateCount;
    private int ga$pieceThinEnd;
    private int ga$pieceBoxEnd;
    private int ga$pieceEncapsulateEnd;
    private int ga$influenceMinX;
    private int ga$influenceMaxX;
    private int ga$influenceMinY;
    private int ga$influenceMaxY;
    private int ga$influenceMinZ;
    private int ga$influenceMaxZ;
    private boolean ga$hasInfluence;
    private NoiseChunk ga$cachedCellChunk;
    private int ga$cachedCellStartX;
    private int ga$cachedCellStartY;
    private int ga$cachedCellStartZ;
    private int ga$cachedCellW;
    private int ga$cachedCellH;
    private int[] ga$cachedActivePieces;
    private int[] ga$cachedActiveJunctions;
    private int ga$cachedActivePieceCount;
    private int ga$cachedActiveJunctionCount;
    private int ga$cachedActiveBuryCount;
    private int ga$cachedActiveThinCount;
    private int ga$cachedActiveBoxCount;
    private int ga$cachedActiveEncapsulateCount;
    private int ga$cachedColumnX = Integer.MIN_VALUE;
    private int ga$cachedColumnZ = Integer.MIN_VALUE;
    private int[] ga$cachedColumnPieces;
    private int[] ga$cachedColumnPieceDx;
    private int[] ga$cachedColumnPieceDz;
    private int[] ga$cachedColumnJunctions;
    private int[] ga$cachedColumnJunctionDx;
    private int[] ga$cachedColumnJunctionDz;
    private int ga$cachedColumnPieceCount;
    private int ga$cachedColumnJunctionCount;
    private int ga$cachedColumnBuryCount;
    private int ga$cachedColumnThinCount;
    private int ga$cachedColumnBoxCount;
    private int ga$cachedColumnEncapsulateCount;

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
        int activePieceCapacity = 0;
        int buryCount = 0;
        int thinCount = 0;
        int boxCount = 0;
        int encapsulateCount = 0;
        for (Beardifier.Rigid piece : pieces) {
            int terrainKind = piece.terrainAdjustment().ordinal();
            if (terrainKind == GA$TERRAIN_NONE) {
                continue;
            }
            activePieceCapacity++;
            if (terrainKind == GA$TERRAIN_BURY) {
                buryCount++;
            } else if (terrainKind == GA$TERRAIN_BEARD_THIN) {
                thinCount++;
            } else if (terrainKind == GA$TERRAIN_BEARD_BOX) {
                boxCount++;
            } else if (terrainKind == GA$TERRAIN_ENCAPSULATE) {
                encapsulateCount++;
            }
        }
        this.ga$pieceBuryCount = buryCount;
        this.ga$pieceThinCount = thinCount;
        this.ga$pieceBoxCount = boxCount;
        this.ga$pieceEncapsulateCount = encapsulateCount;
        this.ga$pieceThinEnd = buryCount + thinCount;
        this.ga$pieceBoxEnd = this.ga$pieceThinEnd + boxCount;
        this.ga$pieceEncapsulateEnd = this.ga$pieceBoxEnd + encapsulateCount;
        this.c2me$pieceMinX = new int[activePieceCapacity];
        this.c2me$pieceMaxX = new int[activePieceCapacity];
        this.c2me$pieceMinY = new int[activePieceCapacity];
        this.c2me$pieceMaxY = new int[activePieceCapacity];
        this.c2me$pieceMinZ = new int[activePieceCapacity];
        this.c2me$pieceMaxZ = new int[activePieceCapacity];
        this.c2me$pieceGroundY = new int[activePieceCapacity];
        this.c2me$pieceInfluenceMinX = new int[activePieceCapacity];
        this.c2me$pieceInfluenceMaxX = new int[activePieceCapacity];
        this.c2me$pieceInfluenceMinY = new int[activePieceCapacity];
        this.c2me$pieceInfluenceMaxY = new int[activePieceCapacity];
        this.c2me$pieceInfluenceMinZ = new int[activePieceCapacity];
        this.c2me$pieceInfluenceMaxZ = new int[activePieceCapacity];
        this.c2me$pieceTerrain = new byte[activePieceCapacity];
        int[] terrainEnds = new int[4];
        GABeardifierTerrainRanges.fillEnds(buryCount, thinCount, boxCount, encapsulateCount, terrainEnds);
        int buryPos = 0;
        int thinPos = terrainEnds[0];
        int boxPos = terrainEnds[1];
        int encapsulatePos = terrainEnds[2];
        for (int i = 0; i < pieces.length; i++) {
            Beardifier.Rigid piece = pieces[i];
            BoundingBox box = piece.box();
            int terrainKind = piece.terrainAdjustment().ordinal();
            if (terrainKind == GA$TERRAIN_NONE) {
                continue;
            }
            int writeIndex;
            if (terrainKind == GA$TERRAIN_BURY) {
                writeIndex = buryPos++;
            } else if (terrainKind == GA$TERRAIN_BEARD_THIN) {
                writeIndex = thinPos++;
            } else if (terrainKind == GA$TERRAIN_BEARD_BOX) {
                writeIndex = boxPos++;
            } else {
                writeIndex = encapsulatePos++;
            }
            int minX = box.minX();
            int maxX = box.maxX();
            int minY = box.minY();
            int maxY = box.maxY();
            int minZ = box.minZ();
            int maxZ = box.maxZ();
            int groundY = minY + piece.groundLevelDelta();
            this.c2me$pieceMinX[writeIndex] = minX;
            this.c2me$pieceMaxX[writeIndex] = maxX;
            this.c2me$pieceMinY[writeIndex] = minY;
            this.c2me$pieceMaxY[writeIndex] = maxY;
            this.c2me$pieceMinZ[writeIndex] = minZ;
            this.c2me$pieceMaxZ[writeIndex] = maxZ;
            this.c2me$pieceGroundY[writeIndex] = groundY;
            this.c2me$pieceTerrain[writeIndex] = (byte) terrainKind;
            this.ga$initPieceInfluenceBounds(writeIndex, terrainKind, minX, maxX, minY, maxY, minZ, maxZ, groundY);
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
        for (int pieceIndex = 0; pieceIndex < terrain.length; pieceIndex++) {
            if (i < influenceMinX[pieceIndex] || i > influenceMaxX[pieceIndex]
                    || j < influenceMinY[pieceIndex] || j > influenceMaxY[pieceIndex]
                    || k < influenceMinZ[pieceIndex] || k > influenceMaxZ[pieceIndex]) {
                continue;
            }
            int dx = minX[pieceIndex] - i;
            if (dx < 0) {
                dx = i - maxX[pieceIndex];
                if (dx < 0) {
                    dx = 0;
                }
            }
            int dz = minZ[pieceIndex] - k;
            if (dz < 0) {
                dz = k - maxZ[pieceIndex];
                if (dz < 0) {
                    dz = 0;
                }
            }

            int terrainKind = terrain[pieceIndex] & 0xFF;
            if (terrainKind == GA$TERRAIN_BURY) {
                d += GABeardifierContributionMath.bury(dx, j - groundY[pieceIndex], dz);
            } else if (terrainKind == GA$TERRAIN_BEARD_THIN) {
                d += ga$getBeardContributionSameY(dx, j - groundY[pieceIndex], dz) * 0.8D;
            } else if (terrainKind == GA$TERRAIN_BEARD_BOX) {
                int ground = groundY[pieceIndex];
                int verticalOffset = j - ground;
                int yDistance = ground - j;
                if (yDistance < 0) {
                    yDistance = j - maxY[pieceIndex];
                    if (yDistance < 0) {
                        yDistance = 0;
                    }
                }
                d += ga$getBeardContributionUnchecked(dx, yDistance, dz, verticalOffset) * 0.8D;
            } else if (terrainKind == GA$TERRAIN_ENCAPSULATE) {
                int yDistance = minY[pieceIndex] - j;
                if (yDistance < 0) {
                    yDistance = j - maxY[pieceIndex];
                    if (yDistance < 0) {
                        yDistance = 0;
                    }
                }
                d += GABeardifierContributionMath.buryHalfScaled(dx, yDistance, dz) * 0.8D;
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
    private double ga$computeAtActive(
            int i,
            int j,
            int k,
            int[] activePieces,
            int buryCount,
            int thinCount,
            int boxCount,
            int encapsulateCount,
            int[] activeJunctions,
            int activeJunctionCount
    ) {
        double d = 0.0;

        int[] minX = this.c2me$pieceMinX;
        int[] maxX = this.c2me$pieceMaxX;
        int[] minY = this.c2me$pieceMinY;
        int[] maxY = this.c2me$pieceMaxY;
        int[] minZ = this.c2me$pieceMinZ;
        int[] maxZ = this.c2me$pieceMaxZ;
        int[] groundY = this.c2me$pieceGroundY;
        int buryEnd = buryCount;
        int thinEnd = buryEnd + thinCount;
        int boxEnd = thinEnd + boxCount;
        int encapsulateEnd = boxEnd + encapsulateCount;
        for (int activeIndex = 0; activeIndex < buryEnd; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int dx = GABeardifierColumnMath.axisDistance(i, minX[pieceIndex], maxX[pieceIndex]);
            if (dx > GA$BURY_RADIUS) {
                continue;
            }
            int dz = GABeardifierColumnMath.axisDistance(k, minZ[pieceIndex], maxZ[pieceIndex]);
            if (dz > GA$BURY_RADIUS) {
                continue;
            }
            int verticalOffset = j - groundY[pieceIndex];
            if (verticalOffset < -11 || verticalOffset > 11) {
                continue;
            }
            d += GABeardifierContributionMath.bury(dx, verticalOffset, dz);
        }
        for (int activeIndex = buryEnd; activeIndex < thinEnd; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int dx = GABeardifierColumnMath.axisDistance(i, minX[pieceIndex], maxX[pieceIndex]);
            if (dx > GA$BEARD_RADIUS) {
                continue;
            }
            int dz = GABeardifierColumnMath.axisDistance(k, minZ[pieceIndex], maxZ[pieceIndex]);
            if (dz > GA$BEARD_RADIUS) {
                continue;
            }
            int verticalOffset = j - groundY[pieceIndex];
            if (!ga$inKernelRange(verticalOffset)) {
                continue;
            }
            d += ga$getBeardContributionSameY(dx, verticalOffset, dz) * 0.8D;
        }
        for (int activeIndex = thinEnd; activeIndex < boxEnd; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int dx = GABeardifierColumnMath.axisDistance(i, minX[pieceIndex], maxX[pieceIndex]);
            if (dx > GA$BEARD_RADIUS) {
                continue;
            }
            int dz = GABeardifierColumnMath.axisDistance(k, minZ[pieceIndex], maxZ[pieceIndex]);
            if (dz > GA$BEARD_RADIUS) {
                continue;
            }
            int ground = groundY[pieceIndex];
            int verticalOffset = j - ground;
            if (verticalOffset < -11 || j > maxY[pieceIndex] + 11) {
                continue;
            }
            int yDistance = ground - j;
            if (yDistance < 0) {
                yDistance = j - maxY[pieceIndex];
                if (yDistance < 0) {
                    yDistance = 0;
                }
            }
            d += ga$getBeardContributionUnchecked(dx, yDistance, dz, verticalOffset) * 0.8D;
        }
        for (int activeIndex = boxEnd; activeIndex < encapsulateEnd; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int dx = GABeardifierColumnMath.axisDistance(i, minX[pieceIndex], maxX[pieceIndex]);
            if (dx > GA$BEARD_RADIUS) {
                continue;
            }
            int dz = GABeardifierColumnMath.axisDistance(k, minZ[pieceIndex], maxZ[pieceIndex]);
            if (dz > GA$BEARD_RADIUS || j < minY[pieceIndex] - 11 || j > maxY[pieceIndex] + 11) {
                continue;
            }
            int yDistance = minY[pieceIndex] - j;
            if (yDistance < 0) {
                yDistance = j - maxY[pieceIndex];
                if (yDistance < 0) {
                    yDistance = 0;
                }
            }
            d += GABeardifierContributionMath.buryHalfScaled(dx, yDistance, dz) * 0.8D;
        }

        int[] junctionX = this.c2me$junctionX;
        int[] junctionY = this.c2me$junctionY;
        int[] junctionZ = this.c2me$junctionZ;
        for (int activeIndex = 0; activeIndex < activeJunctionCount; activeIndex++) {
            int i1 = activeJunctions[activeIndex];
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
    private double ga$computeColumnAtFiltered(
            int j,
            int[] activePieces,
            int buryCount,
            int thinCount,
            int boxCount,
            int encapsulateCount,
            int[] pieceDx,
            int[] pieceDz,
            int[] activeJunctions,
            int activeJunctionCount,
            int[] junctionDx,
            int[] junctionDz
    ) {
        double d = 0.0;

        int[] minY = this.c2me$pieceMinY;
        int[] maxY = this.c2me$pieceMaxY;
        int[] groundY = this.c2me$pieceGroundY;
        int[] influenceMinY = this.c2me$pieceInfluenceMinY;
        int[] influenceMaxY = this.c2me$pieceInfluenceMaxY;
        int buryEnd = buryCount;
        int thinEnd = buryEnd + thinCount;
        int boxEnd = thinEnd + boxCount;
        int encapsulateEnd = boxEnd + encapsulateCount;
        for (int activeIndex = 0; activeIndex < buryEnd; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            int verticalOffset = j - groundY[pieceIndex];
            if (verticalOffset < -11 || verticalOffset > 11) {
                continue;
            }
            d += GABeardifierContributionMath.bury(dx, verticalOffset, dz);
        }
        for (int activeIndex = buryEnd; activeIndex < thinEnd; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            int verticalOffset = j - groundY[pieceIndex];
            if (!ga$inKernelRange(verticalOffset)) {
                continue;
            }
            d += ga$getBeardContributionSameY(dx, verticalOffset, dz) * 0.8D;
        }
        for (int activeIndex = thinEnd; activeIndex < boxEnd; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            if (j < influenceMinY[pieceIndex] || j > influenceMaxY[pieceIndex]) {
                continue;
            }
            int ground = groundY[pieceIndex];
            int verticalOffset = j - ground;
            int yDistance = ground - j;
            if (yDistance < 0) {
                yDistance = j - maxY[pieceIndex];
                if (yDistance < 0) {
                    yDistance = 0;
                }
            }
            d += ga$getBeardContributionUnchecked(dx, yDistance, dz, verticalOffset) * 0.8D;
        }
        for (int activeIndex = boxEnd; activeIndex < encapsulateEnd; activeIndex++) {
            int pieceIndex = activePieces[activeIndex];
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            if (j < influenceMinY[pieceIndex] || j > influenceMaxY[pieceIndex]) {
                continue;
            }
            int yDistance = minY[pieceIndex] - j;
            if (yDistance < 0) {
                yDistance = j - maxY[pieceIndex];
                if (yDistance < 0) {
                    yDistance = 0;
                }
            }
            d += GABeardifierContributionMath.buryHalfScaled(dx, yDistance, dz) * 0.8D;
        }

        int[] junctionY = this.c2me$junctionY;
        for (int activeIndex = 0; activeIndex < activeJunctionCount; activeIndex++) {
            int dx = junctionDx[activeIndex];
            int dz = junctionDz[activeIndex];
            int junctionIndex = activeJunctions[activeIndex];
            int dy = j - junctionY[junctionIndex];
            if (!ga$inKernelRange(dy)) {
                continue;
            }
            d += ga$getBeardContributionSameY(dx, dy, dz) * 0.4D;
        }

        return d;
    }

    @Unique
    private void ga$ensureArraysReady() {
        if (this.c2me$pieceTerrain == null || this.c2me$junctionX == null) {
            this.c2me$initArrays();
        }
    }

    @Unique
    private boolean ga$cellOutsideInfluence(NoiseChunk chunk, int cellW, int cellH) {
        return !this.ga$hasInfluence
                || chunk.cellStartBlockX > this.ga$influenceMaxX
                || chunk.cellStartBlockX + cellW - 1 < this.ga$influenceMinX
                || chunk.cellStartBlockY > this.ga$influenceMaxY
                || chunk.cellStartBlockY + cellH - 1 < this.ga$influenceMinY
                || chunk.cellStartBlockZ > this.ga$influenceMaxZ
                || chunk.cellStartBlockZ + cellW - 1 < this.ga$influenceMinZ;
    }

    @Unique
    private int ga$collectActivePieces(NoiseChunk chunk, int cellW, int cellH, int[] activePieces) {
        int minX = chunk.cellStartBlockX;
        int maxX = minX + cellW - 1;
        int minY = chunk.cellStartBlockY;
        int maxY = minY + cellH - 1;
        int minZ = chunk.cellStartBlockZ;
        int maxZ = minZ + cellW - 1;
        int count = 0;
        byte[] terrain = this.c2me$pieceTerrain;
        int[] influenceMinX = this.c2me$pieceInfluenceMinX;
        int[] influenceMaxX = this.c2me$pieceInfluenceMaxX;
        int[] influenceMinY = this.c2me$pieceInfluenceMinY;
        int[] influenceMaxY = this.c2me$pieceInfluenceMaxY;
        int[] influenceMinZ = this.c2me$pieceInfluenceMinZ;
        int[] influenceMaxZ = this.c2me$pieceInfluenceMaxZ;
        for (int i = 0; i < terrain.length; i++) {
            if (maxX < influenceMinX[i] || minX > influenceMaxX[i]
                    || maxY < influenceMinY[i] || minY > influenceMaxY[i]
                    || maxZ < influenceMinZ[i] || minZ > influenceMaxZ[i]) {
                continue;
            }
            activePieces[count++] = i;
        }
        return count;
    }

    @Unique
    private int ga$collectActiveJunctions(NoiseChunk chunk, int cellW, int cellH, int[] activeJunctions) {
        int minX = chunk.cellStartBlockX;
        int maxX = minX + cellW - 1;
        int minY = chunk.cellStartBlockY;
        int maxY = minY + cellH - 1;
        int minZ = chunk.cellStartBlockZ;
        int maxZ = minZ + cellW - 1;
        int count = 0;
        int[] junctionX = this.c2me$junctionX;
        int[] junctionY = this.c2me$junctionY;
        int[] junctionZ = this.c2me$junctionZ;
        for (int i = 0; i < junctionX.length; i++) {
            if (maxX < junctionX[i] - 12 || minX > junctionX[i] + 11
                    || maxY < junctionY[i] - 12 || minY > junctionY[i] + 11
                    || maxZ < junctionZ[i] - 12 || minZ > junctionZ[i] + 11) {
                continue;
            }
            activeJunctions[count++] = i;
        }
        return count;
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
        double contribution = -y * Mth.fastInvSqrt(lengthSquared * 0.5d) * 0.5d;
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
                        double contribution = -y * Mth.fastInvSqrt(lengthSquared * 0.5d) * 0.5d;
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
        return GABeardifierContributionMath.buryDistance(x, y, z);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public double compute(FunctionContext context) {
        if (context instanceof NoiseChunk chunk) {
            return this.ga$computeAtNoiseChunkCell(chunk);
        }
        return this.c2me$computeAt(context.blockX(), context.blockY(), context.blockZ());
    }

    @Unique
    private double ga$computeAtNoiseChunkCell(NoiseChunk chunk) {
        long sampledStart = BeardifierStats.sampleComputeCellStart();
        this.ga$ensureArraysReady();
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        if (this.ga$cellOutsideInfluence(chunk, cellW, cellH)) {
            BeardifierStats.recordOutsideInfluenceReturn();
            return ga$finishSampledCompute(0.0D, sampledStart);
        }
        if (this.ga$cachedCellChunk != chunk
                || this.ga$cachedCellStartX != chunk.cellStartBlockX
                || this.ga$cachedCellStartY != chunk.cellStartBlockY
                || this.ga$cachedCellStartZ != chunk.cellStartBlockZ
                || this.ga$cachedCellW != cellW
                || this.ga$cachedCellH != cellH) {
            GABeardifierCellScratch scratch = GA$CELL_SCRATCH.get();
            int[] activePieces = scratch.pieces(this.c2me$pieceTerrain.length);
            int activePieceCount = this.ga$collectActivePieces(chunk, cellW, cellH, activePieces);
            int[] terrainCounts = scratch.terrainCounts();
            if (activePieceCount > 0) {
                GABeardifierActivePieces.countSortedByTerrain(
                        activePieces,
                        activePieceCount,
                        this.ga$pieceBuryCount,
                        this.ga$pieceThinEnd,
                        this.ga$pieceBoxEnd,
                        terrainCounts
                );
            } else {
                Arrays.fill(terrainCounts, 0);
            }
            int[] activeJunctions = scratch.junctions(this.c2me$junctionX.length);
            int activeJunctionCount = this.ga$collectActiveJunctions(chunk, cellW, cellH, activeJunctions);
            this.ga$cachedCellChunk = chunk;
            this.ga$cachedCellStartX = chunk.cellStartBlockX;
            this.ga$cachedCellStartY = chunk.cellStartBlockY;
            this.ga$cachedCellStartZ = chunk.cellStartBlockZ;
            this.ga$cachedCellW = cellW;
            this.ga$cachedCellH = cellH;
            this.ga$cachedActivePieces = activePieces;
            this.ga$cachedActiveJunctions = activeJunctions;
            this.ga$cachedActivePieceCount = activePieceCount;
            this.ga$cachedActiveJunctionCount = activeJunctionCount;
            this.ga$cachedActiveBuryCount = terrainCounts[0];
            this.ga$cachedActiveThinCount = terrainCounts[1];
            this.ga$cachedActiveBoxCount = terrainCounts[2];
            this.ga$cachedActiveEncapsulateCount = terrainCounts[3];
            this.ga$cachedColumnX = Integer.MIN_VALUE;
            this.ga$cachedColumnZ = Integer.MIN_VALUE;
            this.ga$cachedColumnPieceCount = 0;
            this.ga$cachedColumnJunctionCount = 0;
        }
        if (this.ga$cachedActivePieceCount == 0 && this.ga$cachedActiveJunctionCount == 0) {
            BeardifierStats.recordEmptyActiveReturn();
            return ga$finishSampledCompute(0.0D, sampledStart);
        }
        BeardifierStats.recordComputeCell(this.ga$cachedActivePieceCount, this.ga$cachedActiveJunctionCount);
        int blockX = chunk.cellStartBlockX + chunk.inCellX;
        int blockY = chunk.cellStartBlockY + chunk.inCellY;
        int blockZ = chunk.cellStartBlockZ + chunk.inCellZ;
        if (this.ga$cachedColumnX == blockX && this.ga$cachedColumnZ == blockZ) {
            BeardifierStats.recordColumnCacheHit();
            return ga$finishSampledCompute(this.ga$computeColumnAtFiltered(
                    blockY,
                    this.ga$cachedColumnPieces,
                    this.ga$cachedColumnBuryCount,
                    this.ga$cachedColumnThinCount,
                    this.ga$cachedColumnBoxCount,
                    this.ga$cachedColumnEncapsulateCount,
                    this.ga$cachedColumnPieceDx,
                    this.ga$cachedColumnPieceDz,
                    this.ga$cachedColumnJunctions,
                    this.ga$cachedColumnJunctionCount,
                    this.ga$cachedColumnJunctionDx,
                    this.ga$cachedColumnJunctionDz
            ), sampledStart);
        }
        BeardifierStats.recordDirectComputeFallback();
        long directSampledStart = BeardifierStats.sampleDirectComputeStart();
        double result = this.ga$computeAtActive(
                blockX,
                blockY,
                blockZ,
                this.ga$cachedActivePieces,
                this.ga$cachedActiveBuryCount,
                this.ga$cachedActiveThinCount,
                this.ga$cachedActiveBoxCount,
                this.ga$cachedActiveEncapsulateCount,
                this.ga$cachedActiveJunctions,
                this.ga$cachedActiveJunctionCount
        );
        if (directSampledStart != 0L) {
            BeardifierStats.recordDirectComputeTimed(System.nanoTime() - directSampledStart);
        }
        return ga$finishSampledCompute(result, sampledStart);
    }

    @Unique
    private static double ga$finishSampledCompute(double value, long sampledStart) {
        if (sampledStart != 0L) {
            BeardifierStats.recordComputeCellTimed(System.nanoTime() - sampledStart);
        }
        return value;
    }

    @Unique
    private void ga$rebuildCachedColumn(int blockX, int blockZ, int startY, int cellH) {
        long sampledStart = BeardifierStats.sampleRebuildColumnStart();
        GABeardifierCellScratch scratch = GA$CELL_SCRATCH.get();
        int activePieceCount = this.ga$cachedActivePieceCount;
        int activeJunctionCount = this.ga$cachedActiveJunctionCount;
        int[] pieceDx = scratch.pieceDx(activePieceCount);
        int[] pieceDz = scratch.pieceDz(activePieceCount);
        int[] junctionDx = scratch.junctionDx(activeJunctionCount);
        int[] junctionDz = scratch.junctionDz(activeJunctionCount);
        int[] columnPieces = scratch.columnPieces(activePieceCount);
        int[] columnJunctions = scratch.columnJunctions(activeJunctionCount);
        int[] columnTerrainCounts = scratch.columnTerrainCounts();
        int[] columnPieceDx = scratch.columnPieceDx(activePieceCount);
        int[] columnPieceDz = scratch.columnPieceDz(activePieceCount);
        int[] columnJunctionDx = scratch.columnJunctionDx(activeJunctionCount);
        int[] columnJunctionDz = scratch.columnJunctionDz(activeJunctionCount);

        GABeardifierColumnMath.fillPieceDistances(
                blockX,
                blockZ,
                this.ga$cachedActivePieces,
                activePieceCount,
                this.c2me$pieceMinX,
                this.c2me$pieceMaxX,
                this.c2me$pieceMinZ,
                this.c2me$pieceMaxZ,
                pieceDx,
                pieceDz
        );
        GABeardifierColumnMath.fillJunctionOffsets(
                blockX,
                blockZ,
                this.ga$cachedActiveJunctions,
                activeJunctionCount,
                this.c2me$junctionX,
                this.c2me$junctionZ,
                junctionDx,
                junctionDz
        );
        int columnPieceCount = GABeardifierColumnFilter.filterPiecesByColumn(
                this.ga$cachedActivePieces,
                activePieceCount,
                pieceDx,
                pieceDz,
                this.c2me$pieceInfluenceMinY,
                this.c2me$pieceInfluenceMaxY,
                startY,
                startY + cellH - 1,
                this.ga$cachedActiveBuryCount,
                this.ga$cachedActiveBuryCount + this.ga$cachedActiveThinCount,
                this.ga$cachedActiveBuryCount + this.ga$cachedActiveThinCount + this.ga$cachedActiveBoxCount,
                columnPieces,
                columnPieceDx,
                columnPieceDz,
                columnTerrainCounts
        );
        int columnJunctionCount = GABeardifierColumnFilter.filterJunctionsByColumn(
                this.ga$cachedActiveJunctions,
                activeJunctionCount,
                junctionDx,
                junctionDz,
                this.c2me$junctionY,
                startY,
                startY + cellH - 1,
                columnJunctions,
                columnJunctionDx,
                columnJunctionDz
        );
        BeardifierStats.recordColumnFilter(
                activePieceCount,
                columnPieceCount,
                activeJunctionCount,
                columnJunctionCount,
                columnTerrainCounts[0],
                columnTerrainCounts[1],
                columnTerrainCounts[2],
                columnTerrainCounts[3]
        );

        this.ga$cachedColumnX = blockX;
        this.ga$cachedColumnZ = blockZ;
        this.ga$cachedColumnPieces = columnPieces;
        this.ga$cachedColumnPieceDx = columnPieceDx;
        this.ga$cachedColumnPieceDz = columnPieceDz;
        this.ga$cachedColumnJunctions = columnJunctions;
        this.ga$cachedColumnJunctionDx = columnJunctionDx;
        this.ga$cachedColumnJunctionDz = columnJunctionDz;
        this.ga$cachedColumnPieceCount = columnPieceCount;
        this.ga$cachedColumnJunctionCount = columnJunctionCount;
        this.ga$cachedColumnBuryCount = columnTerrainCounts[0];
        this.ga$cachedColumnThinCount = columnTerrainCounts[1];
        this.ga$cachedColumnBoxCount = columnTerrainCounts[2];
        this.ga$cachedColumnEncapsulateCount = columnTerrainCounts[3];
        if (sampledStart != 0L) {
            BeardifierStats.recordRebuildColumnTimed(System.nanoTime() - sampledStart);
        }
    }

    @Override
    public void dfc$fillCell(double[] out, NoiseChunk chunk) {
        this.ga$ensureArraysReady();
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        int cellValues = cellW * cellW * cellH;
        if (this.ga$cellOutsideInfluence(chunk, cellW, cellH)) {
            Arrays.fill(out, 0, cellValues, 0.0D);
            chunk.arrayIndex = cellValues;
            return;
        }
        GABeardifierCellScratch scratch = GA$CELL_SCRATCH.get();
        int[] activePieces = scratch.pieces(this.c2me$pieceTerrain.length);
        int activePieceCount = this.ga$collectActivePieces(chunk, cellW, cellH, activePieces);
        int[] terrainCounts = scratch.terrainCounts();
        if (activePieceCount > 0) {
            GABeardifierActivePieces.countSortedByTerrain(
                    activePieces,
                    activePieceCount,
                    this.ga$pieceBuryCount,
                    this.ga$pieceThinEnd,
                    this.ga$pieceBoxEnd,
                    terrainCounts
            );
        } else {
            Arrays.fill(terrainCounts, 0);
        }
        int[] activeJunctions = scratch.junctions(this.c2me$junctionX.length);
        int activeJunctionCount = this.ga$collectActiveJunctions(chunk, cellW, cellH, activeJunctions);
        if (activePieceCount == 0 && activeJunctionCount == 0) {
            Arrays.fill(out, 0, cellValues, 0.0D);
            chunk.arrayIndex = cellValues;
            return;
        }
        BeardifierStats.recordFillCell(activePieceCount, activeJunctionCount);

        int idx = 0;
        int startX = chunk.cellStartBlockX;
        int startY = chunk.cellStartBlockY;
        int startZ = chunk.cellStartBlockZ;
        int[] pieceDx = scratch.pieceDx(activePieceCount);
        int[] pieceDz = scratch.pieceDz(activePieceCount);
        int[] junctionDx = scratch.junctionDx(activeJunctionCount);
        int[] junctionDz = scratch.junctionDz(activeJunctionCount);
        int[] columnPieces = scratch.columnPieces(activePieceCount);
        int[] columnJunctions = scratch.columnJunctions(activeJunctionCount);
        int[] columnTerrainCounts = scratch.columnTerrainCounts();
        int[] columnPieceDx = scratch.columnPieceDx(activePieceCount);
        int[] columnPieceDz = scratch.columnPieceDz(activePieceCount);
        int[] columnJunctionDx = scratch.columnJunctionDx(activeJunctionCount);
        int[] columnJunctionDz = scratch.columnJunctionDz(activeJunctionCount);
        chunk.arrayIndex = 0;
        for (int inCellX = 0; inCellX < cellW; inCellX++) {
            chunk.inCellX = inCellX;
            int blockX = startX + inCellX;
            for (int inCellZ = 0; inCellZ < cellW; inCellZ++) {
                chunk.inCellZ = inCellZ;
                int blockZ = startZ + inCellZ;
                GABeardifierColumnMath.fillPieceDistances(
                        blockX,
                        blockZ,
                        activePieces,
                        activePieceCount,
                        this.c2me$pieceMinX,
                        this.c2me$pieceMaxX,
                        this.c2me$pieceMinZ,
                        this.c2me$pieceMaxZ,
                        pieceDx,
                        pieceDz
                );
                GABeardifierColumnMath.fillJunctionOffsets(
                        blockX,
                        blockZ,
                        activeJunctions,
                        activeJunctionCount,
                        this.c2me$junctionX,
                        this.c2me$junctionZ,
                        junctionDx,
                        junctionDz
                );
                int columnPieceCount = GABeardifierColumnFilter.filterPiecesByColumn(
                        activePieces,
                        activePieceCount,
                        pieceDx,
                        pieceDz,
                        this.c2me$pieceInfluenceMinY,
                        this.c2me$pieceInfluenceMaxY,
                        startY,
                        startY + cellH - 1,
                        terrainCounts[0],
                        terrainCounts[0] + terrainCounts[1],
                        terrainCounts[0] + terrainCounts[1] + terrainCounts[2],
                        columnPieces,
                        columnPieceDx,
                        columnPieceDz,
                        columnTerrainCounts
                );
                int columnJunctionCount = GABeardifierColumnFilter.filterJunctionsByColumn(
                        activeJunctions,
                        activeJunctionCount,
                        junctionDx,
                        junctionDz,
                        this.c2me$junctionY,
                        startY,
                        startY + cellH - 1,
                        columnJunctions,
                        columnJunctionDx,
                        columnJunctionDz
                );
                BeardifierStats.recordColumnFilter(
                        activePieceCount,
                        columnPieceCount,
                        activeJunctionCount,
                        columnJunctionCount,
                        columnTerrainCounts[0],
                        columnTerrainCounts[1],
                        columnTerrainCounts[2],
                        columnTerrainCounts[3]
                );
                if (columnPieceCount == 0 && columnJunctionCount == 0) {
                    idx += cellH;
                    continue;
                }
                for (int inCellY = cellH - 1; inCellY >= 0; inCellY--) {
                    chunk.inCellY = inCellY;
                    chunk.arrayIndex = idx;
                    out[idx] = this.ga$computeColumnAtFiltered(
                            startY + inCellY,
                            columnPieces,
                            columnTerrainCounts[0],
                            columnTerrainCounts[1],
                            columnTerrainCounts[2],
                            columnTerrainCounts[3],
                            columnPieceDx,
                            columnPieceDz,
                            columnJunctions,
                            columnJunctionCount,
                            columnJunctionDx,
                            columnJunctionDz
                    );
                    idx++;
                }
            }
        }
        chunk.arrayIndex = idx;
    }

    @Override
    public void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        this.ga$ensureArraysReady();
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        int cellValues = cellW * cellW * cellH;
        if (this.ga$cellOutsideInfluence(chunk, cellW, cellH)) {
            chunk.arrayIndex = cellValues;
            return;
        }
        GABeardifierCellScratch scratch = GA$CELL_SCRATCH.get();
        int[] activePieces = scratch.pieces(this.c2me$pieceTerrain.length);
        int activePieceCount = this.ga$collectActivePieces(chunk, cellW, cellH, activePieces);
        int[] terrainCounts = scratch.terrainCounts();
        if (activePieceCount > 0) {
            GABeardifierActivePieces.countSortedByTerrain(
                    activePieces,
                    activePieceCount,
                    this.ga$pieceBuryCount,
                    this.ga$pieceThinEnd,
                    this.ga$pieceBoxEnd,
                    terrainCounts
            );
        } else {
            Arrays.fill(terrainCounts, 0);
        }
        int[] activeJunctions = scratch.junctions(this.c2me$junctionX.length);
        int activeJunctionCount = this.ga$collectActiveJunctions(chunk, cellW, cellH, activeJunctions);
        if (activePieceCount == 0 && activeJunctionCount == 0) {
            chunk.arrayIndex = cellValues;
            return;
        }
        BeardifierStats.recordAccumulateCell(activePieceCount, activeJunctionCount);

        int idx = 0;
        int startX = chunk.cellStartBlockX;
        int startY = chunk.cellStartBlockY;
        int startZ = chunk.cellStartBlockZ;
        int[] pieceDx = scratch.pieceDx(activePieceCount);
        int[] pieceDz = scratch.pieceDz(activePieceCount);
        int[] junctionDx = scratch.junctionDx(activeJunctionCount);
        int[] junctionDz = scratch.junctionDz(activeJunctionCount);
        int[] columnPieces = scratch.columnPieces(activePieceCount);
        int[] columnJunctions = scratch.columnJunctions(activeJunctionCount);
        int[] columnTerrainCounts = scratch.columnTerrainCounts();
        int[] columnPieceDx = scratch.columnPieceDx(activePieceCount);
        int[] columnPieceDz = scratch.columnPieceDz(activePieceCount);
        int[] columnJunctionDx = scratch.columnJunctionDx(activeJunctionCount);
        int[] columnJunctionDz = scratch.columnJunctionDz(activeJunctionCount);
        chunk.arrayIndex = 0;
        for (int inCellX = 0; inCellX < cellW; inCellX++) {
            chunk.inCellX = inCellX;
            int blockX = startX + inCellX;
            for (int inCellZ = 0; inCellZ < cellW; inCellZ++) {
                chunk.inCellZ = inCellZ;
                int blockZ = startZ + inCellZ;
                GABeardifierColumnMath.fillPieceDistances(
                        blockX,
                        blockZ,
                        activePieces,
                        activePieceCount,
                        this.c2me$pieceMinX,
                        this.c2me$pieceMaxX,
                        this.c2me$pieceMinZ,
                        this.c2me$pieceMaxZ,
                        pieceDx,
                        pieceDz
                );
                GABeardifierColumnMath.fillJunctionOffsets(
                        blockX,
                        blockZ,
                        activeJunctions,
                        activeJunctionCount,
                        this.c2me$junctionX,
                        this.c2me$junctionZ,
                        junctionDx,
                        junctionDz
                );
                int columnPieceCount = GABeardifierColumnFilter.filterPiecesByColumn(
                        activePieces,
                        activePieceCount,
                        pieceDx,
                        pieceDz,
                        this.c2me$pieceInfluenceMinY,
                        this.c2me$pieceInfluenceMaxY,
                        startY,
                        startY + cellH - 1,
                        terrainCounts[0],
                        terrainCounts[0] + terrainCounts[1],
                        terrainCounts[0] + terrainCounts[1] + terrainCounts[2],
                        columnPieces,
                        columnPieceDx,
                        columnPieceDz,
                        columnTerrainCounts
                );
                int columnJunctionCount = GABeardifierColumnFilter.filterJunctionsByColumn(
                        activeJunctions,
                        activeJunctionCount,
                        junctionDx,
                        junctionDz,
                        this.c2me$junctionY,
                        startY,
                        startY + cellH - 1,
                        columnJunctions,
                        columnJunctionDx,
                        columnJunctionDz
                );
                BeardifierStats.recordColumnFilter(
                        activePieceCount,
                        columnPieceCount,
                        activeJunctionCount,
                        columnJunctionCount,
                        columnTerrainCounts[0],
                        columnTerrainCounts[1],
                        columnTerrainCounts[2],
                        columnTerrainCounts[3]
                );
                if (columnPieceCount == 0 && columnJunctionCount == 0) {
                    idx += cellH;
                    continue;
                }
                for (int inCellY = cellH - 1; inCellY >= 0; inCellY--) {
                    chunk.inCellY = inCellY;
                    chunk.arrayIndex = idx;
                    out[idx] += this.ga$computeColumnAtFiltered(
                            startY + inCellY,
                            columnPieces,
                            columnTerrainCounts[0],
                            columnTerrainCounts[1],
                            columnTerrainCounts[2],
                            columnTerrainCounts[3],
                            columnPieceDx,
                            columnPieceDz,
                            columnJunctions,
                            columnJunctionCount,
                            columnJunctionDx,
                            columnJunctionDz
                    );
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
