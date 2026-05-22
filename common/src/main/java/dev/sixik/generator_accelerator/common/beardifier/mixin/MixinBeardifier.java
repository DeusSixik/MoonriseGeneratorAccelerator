package dev.sixik.generator_accelerator.common.beardifier.mixin;

import com.google.common.collect.Iterators;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sixik.generator_accelerator.common.beardifier.GABeardifierCellScratch;
import dev.sixik.generator_accelerator.common.beardifier.GABeardifierKernel;
import dev.sixik.generator_accelerator.common.beardifier.GABeardifierPlan;
import dev.sixik.generator_accelerator.common.beardifier.GABeardifierThreadCache;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

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

    @Unique
    private static final boolean GA$SKIP_BEARDIFIER = Boolean.parseBoolean(System.getProperty(
            "ga.beardifier.skip.enabled",
            "false"
    ));

    @Unique
    private GABeardifierPlan ga$plan;
    @Unique
    private static final ThreadLocal<GABeardifierThreadCache> GA$THREAD_CACHE =
            ThreadLocal.withInitial(GABeardifierThreadCache::new);

    @Unique
    private void ga$initPlan() {
        Beardifier.Rigid[] pieces = Iterators.toArray(this.pieceIterator, Beardifier.Rigid.class);
        this.pieceIterator.back(Integer.MAX_VALUE);
        GABeardifierKernel.setBeardKernel(BEARD_KERNEL);

        int pieceCount = pieces.length;
        int[] pieceMinX = new int[pieceCount];
        int[] pieceMaxX = new int[pieceCount];
        int[] pieceMinY = new int[pieceCount];
        int[] pieceMaxY = new int[pieceCount];
        int[] pieceMinZ = new int[pieceCount];
        int[] pieceMaxZ = new int[pieceCount];
        int[] pieceGroundY = new int[pieceCount];
        byte[] pieceTerrain = new byte[pieceCount];
        for (int i = 0; i < pieceCount; i++) {
            Beardifier.Rigid piece = pieces[i];
            BoundingBox box = piece.box();
            pieceMinX[i] = box.minX();
            pieceMaxX[i] = box.maxX();
            pieceMinY[i] = box.minY();
            pieceMaxY[i] = box.maxY();
            pieceMinZ[i] = box.minZ();
            pieceMaxZ[i] = box.maxZ();
            pieceGroundY[i] = box.minY() + piece.groundLevelDelta();
            pieceTerrain[i] = ga$toKernelTerrainKind(piece.terrainAdjustment());
        }

        JigsawJunction[] junctions = Iterators.toArray(this.junctionIterator, JigsawJunction.class);
        this.junctionIterator.back(Integer.MAX_VALUE);
        int junctionCount = junctions.length;
        int[] junctionX = new int[junctionCount];
        int[] junctionY = new int[junctionCount];
        int[] junctionZ = new int[junctionCount];
        for (int i = 0; i < junctionCount; i++) {
            JigsawJunction junction = junctions[i];
            junctionX[i] = junction.getSourceX();
            junctionY[i] = junction.getSourceGroundY();
            junctionZ[i] = junction.getSourceZ();
        }

        this.ga$plan = GABeardifierPlan.create(
                pieceMinX,
                pieceMaxX,
                pieceMinY,
                pieceMaxY,
                pieceMinZ,
                pieceMaxZ,
                pieceGroundY,
                pieceTerrain,
                junctionX,
                junctionY,
                junctionZ
        );
    }

    @Unique
    private static byte ga$toKernelTerrainKind(TerrainAdjustment terrain) {
        return switch (terrain) {
            case BURY -> GABeardifierKernel.KIND_BURY;
            case BEARD_THIN -> GABeardifierKernel.KIND_BEARD_THIN;
            case BEARD_BOX -> GABeardifierKernel.KIND_BEARD_BOX;
            case ENCAPSULATE -> GABeardifierKernel.KIND_ENCAPSULATE;
            default -> GABeardifierKernel.KIND_NONE;
        };
    }

    @Unique
    private void ga$ensurePlan() {
        if (this.ga$plan == null) {
            this.ga$initPlan();
        }
    }

    @Unique
    private GABeardifierCellScratch ga$scratch() {
        return GA$THREAD_CACHE.get().scratch;
    }

    @Unique
    private double ga$computeAtNoiseChunkCell(NoiseChunk chunk) {
        this.ga$ensurePlan();
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        int minX = chunk.cellStartBlockX;
        int minY = chunk.cellStartBlockY;
        int minZ = chunk.cellStartBlockZ;
        int maxX = minX + cellW - 1;
        int maxY = minY + cellH - 1;
        int maxZ = minZ + cellW - 1;
        if (this.ga$plan.outside(minX, maxX, minY, maxY, minZ, maxZ)) {
            return 0.0D;
        }

        GABeardifierThreadCache cache = GA$THREAD_CACHE.get();
        if (cache.owner != this
                || cache.chunk != chunk
                || cache.startX != minX
                || cache.startY != minY
                || cache.startZ != minZ
                || cache.cellWidth != cellW
                || cache.cellHeight != cellH) {
            this.ga$plan.collectActive(cache.scratch, minX, maxX, minY, maxY, minZ, maxZ);
            cache.owner = this;
            cache.chunk = chunk;
            cache.startX = minX;
            cache.startY = minY;
            cache.startZ = minZ;
            cache.cellWidth = cellW;
            cache.cellHeight = cellH;
        }
        if (cache.scratch.empty()) {
            return 0.0D;
        }
        return GABeardifierKernel.computeCollectedAt(
                this.ga$plan,
                cache.scratch,
                minX + chunk.inCellX,
                minY + chunk.inCellY,
                minZ + chunk.inCellZ
        );
    }

    /**
     * @author Sixik
     * @reason Delegate Beardifier point sampling to primitive detached kernel.
     */
    @Overwrite
    public double compute(FunctionContext context) {
        if (GA$SKIP_BEARDIFIER) {
            return 0.0D;
        }
        if (context instanceof NoiseChunk chunk) {
            return this.ga$computeAtNoiseChunkCell(chunk);
        }
        this.ga$ensurePlan();
        return GABeardifierKernel.computeAt(
                this.ga$plan,
                this.ga$scratch(),
                context.blockX(),
                context.blockY(),
                context.blockZ()
        );
    }

    @Override
    public void dfc$fillCell(double[] out, NoiseChunk chunk) {
        if (GA$SKIP_BEARDIFIER) {
            int cellValues = chunk.cellWidth * chunk.cellWidth * chunk.cellHeight;
            Arrays.fill(out, 0, cellValues, 0.0D);
            chunk.arrayIndex = cellValues;
            return;
        }
        this.ga$ensurePlan();
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        int cellValues = cellW * cellW * cellH;
        if (this.ga$plan.outside(
                chunk.cellStartBlockX,
                chunk.cellStartBlockX + cellW - 1,
                chunk.cellStartBlockY,
                chunk.cellStartBlockY + cellH - 1,
                chunk.cellStartBlockZ,
                chunk.cellStartBlockZ + cellW - 1
        )) {
            Arrays.fill(out, 0, cellValues, 0.0D);
            chunk.arrayIndex = cellValues;
            return;
        }
        GABeardifierThreadCache cache = GA$THREAD_CACHE.get();
        cache.owner = null;
        GABeardifierKernel.fillCell(
                this.ga$plan,
                cache.scratch,
                out,
                cellW,
                cellH,
                chunk.cellStartBlockX,
                chunk.cellStartBlockY,
                chunk.cellStartBlockZ
        );
        chunk.arrayIndex = cellValues;
    }

    @Override
    public void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        if (GA$SKIP_BEARDIFIER) {
            chunk.arrayIndex = chunk.cellWidth * chunk.cellWidth * chunk.cellHeight;
            return;
        }
        this.ga$ensurePlan();
        int cellW = chunk.cellWidth;
        int cellH = chunk.cellHeight;
        int cellValues = cellW * cellW * cellH;
        if (this.ga$plan.outside(
                chunk.cellStartBlockX,
                chunk.cellStartBlockX + cellW - 1,
                chunk.cellStartBlockY,
                chunk.cellStartBlockY + cellH - 1,
                chunk.cellStartBlockZ,
                chunk.cellStartBlockZ + cellW - 1
        )) {
            chunk.arrayIndex = cellValues;
            return;
        }
        GABeardifierThreadCache cache = GA$THREAD_CACHE.get();
        cache.owner = null;
        GABeardifierKernel.accumulateCell(
                this.ga$plan,
                cache.scratch,
                out,
                cellW,
                cellH,
                chunk.cellStartBlockX,
                chunk.cellStartBlockY,
                chunk.cellStartBlockZ
        );
        chunk.arrayIndex = cellValues;
    }

    /**
     * @author Sixik
     * @reason Share the same primitive bury contribution helper with the detached kernel.
     */
    @WrapMethod(method = "getBuryContribution")
    private static double bts$getBuryContribution(double x, double y, double z, Operation<Double> original) {
        return GABeardifierKernel.getBuryContribution(x, y, z);
    }
}
