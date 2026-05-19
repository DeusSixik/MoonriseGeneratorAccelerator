package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructurePieceCollisionIndexTest {
    private static volatile int sink;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void collisionIndexMatchesLinearScan() {
        List<StructurePiece> pieces = pieces(128);
        StructurePieceCollisionIndex index = new StructurePieceCollisionIndex();
        for (StructurePiece piece : pieces) {
            index.add(piece);
        }

        for (BoundingBox query : queries(96)) {
            StructurePiece expected = StructurePiece.findCollisionPiece(pieces, query);
            StructurePiece actual = index.findCollision(query);
            assertEquals(expected, actual, "query=" + query);
        }
    }

    @Test
    void printsCollisionIndexMetrics() {
        int warmup = Integer.getInteger("ga.test.structureCollisionWarmup", 10_000);
        int iterations = Integer.getInteger("ga.test.structureCollisionIterations", 40_000);
        List<StructurePiece> pieces = pieces(256);
        BoundingBox[] queries = queries(256);
        StructurePieceCollisionIndex index = new StructurePieceCollisionIndex();
        for (StructurePiece piece : pieces) {
            index.add(piece);
        }

        runLinear(pieces, queries, warmup);
        runIndexed(index, queries, warmup);
        long linearNanos = timeLinear(pieces, queries, iterations);
        long indexedNanos = timeIndexed(index, queries, iterations);

        System.out.println("StructurePiecesBuilder collision benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", pieces=256, queries=256");
        printMetric("collision-scan", linearNanos, indexedNanos, iterations);
    }

    private static long timeLinear(List<StructurePiece> pieces, BoundingBox[] queries, int iterations) {
        long started = System.nanoTime();
        runLinear(pieces, queries, iterations);
        return System.nanoTime() - started;
    }

    private static void runLinear(List<StructurePiece> pieces, BoundingBox[] queries, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            local += StructurePiece.findCollisionPiece(pieces, queries[i % queries.length]) != null ? 1 : 0;
        }
        sink = local;
    }

    private static long timeIndexed(StructurePieceCollisionIndex index, BoundingBox[] queries, int iterations) {
        long started = System.nanoTime();
        runIndexed(index, queries, iterations);
        return System.nanoTime() - started;
    }

    private static void runIndexed(StructurePieceCollisionIndex index, BoundingBox[] queries, int iterations) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            local += index.findCollision(queries[i % queries.length]) != null ? 1 : 0;
        }
        sink = local;
    }

    private static List<StructurePiece> pieces(int count) {
        List<StructurePiece> pieces = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int minX = (i % 16) * 24;
            int minZ = (i / 16) * 24;
            int minY = 32 + (i & 7) * 3;
            pieces.add(new DummyPiece(new BoundingBox(minX, minY, minZ, minX + 18, minY + 14, minZ + 18)));
        }
        return pieces;
    }

    private static BoundingBox[] queries(int count) {
        BoundingBox[] queries = new BoundingBox[count];
        for (int i = 0; i < count; i++) {
            int minX = (i % 16) * 24 + 6;
            int minZ = (i / 16) * 24 + 4;
            int minY = 30 + (i & 7) * 3;
            queries[i] = new BoundingBox(minX, minY, minZ, minX + 16, minY + 12, minZ + 16);
        }
        return queries;
    }

    private static void printMetric(String label, long previousNanos, long nextNanos, int iterations) {
        double previousNsOp = (double) previousNanos / iterations;
        double nextNsOp = (double) nextNanos / iterations;
        double speedup = previousNsOp / Math.max(1.0D, nextNsOp);
        System.out.printf("%s linear=%.1f ns/op indexed=%.1f ns/op speedup=%.2fx%n",
                label, previousNsOp, nextNsOp, speedup);
    }

    private static final class DummyPiece extends StructurePiece {

        private DummyPiece(BoundingBox box) {
            super(null, 0, box);
        }

        @Override
        protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        }

        @Override
        public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator chunkGenerator, RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pos) {
        }
    }
}
