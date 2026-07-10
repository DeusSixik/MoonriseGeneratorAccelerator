package dev.sixik.generator_accelerator.common.surface_compiler;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.DirectWriteSupport;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.HybridKernelSupport;
import dev.sixik.generator_accelerator.common.surface_compiler.cache.FingerprintCacheKey;
import dev.sixik.generator_accelerator.common.surface_compiler.cow.CowSectionWriter;
import dev.sixik.generator_accelerator.common.surface_compiler.cow.SectionCowManager;
import dev.sixik.generator_accelerator.common.surface_compiler.facts.SurfaceFacts;
import dev.sixik.generator_accelerator.common.surface_compiler.halo.HaloPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceNode;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceStateToken;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionContext;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceTier;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceCommitMode;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SnapshotPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SnapshotResolver;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SurfaceReadSnapshot;
import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.FallbackReason;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class SectionCowManagerTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void clearRuntimeState() {
        SurfaceRuntime.clearCaches();
        SurfaceMetrics.reset();
    }

    @Test
    void rawSectionWritesAreInvisibleUntilCommit() {
        int[] raw = new int[4096];
        LevelChunkSection section = flatSection(raw);
        when(((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class)))
                .thenAnswer(invocation -> {
                    System.arraycopy(invocation.getArgument(0), 0, raw, 0, raw.length);
                    return true;
                });
        ChunkAccess chunk = chunk(section);
        SectionCowManager manager = new SectionCowManager(chunk);
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        int index = localIndex(1, 2, 3);

        CowSectionWriter writer = manager.writerForY(2);
        writer.setBlockState(1, 2, 3, dirt);

        assertTrue(manager.dirty());
        assertEquals(Block.getId(Blocks.AIR.defaultBlockState()), raw[index]);
        verify(section, never()).setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(false));

        manager.commit();

        assertTrue(manager.committed());
        assertEquals(Block.getId(dirt), raw[index]);
        verify((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class));
        verify(section, never()).setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(false));
    }

    @Test
    void discardDropsShadowWritesWithoutMutatingSection() {
        int[] raw = new int[4096];
        LevelChunkSection section = flatSection(raw);
        ChunkAccess chunk = chunk(section);
        SectionCowManager manager = new SectionCowManager(chunk);
        int index = localIndex(4, 5, 6);

        manager.writerForY(5).setBlockState(4, 5, 6, Blocks.STONE.defaultBlockState());
        manager.discard();

        assertTrue(manager.discarded());
        assertFalse(manager.committed());
        assertEquals(Block.getId(Blocks.AIR.defaultBlockState()), raw[index]);
        verify((LevelChunkSection$FlatBlockArray) section, never()).bts$copyRawBlockDataForGeneration(any(int[].class));
        verify(section, never()).setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(false));
        assertThrows(IllegalStateException.class, () -> manager.writerForY(5));
        assertThrows(IllegalStateException.class, manager::commit);
    }

    @Test
    void runtimeFailureDiscardsCowAndQuarantinesForCleanFallback() {
        SurfaceRules.RuleSource rule = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(rule);
        ChunkAccess brokenChunk = mock(ChunkAccess.class);
        when(brokenChunk.getPos()).thenReturn(new ChunkPos(0, 0));
        when(brokenChunk.getMinBuildHeight()).thenReturn(0);
        when(brokenChunk.getSections()).thenThrow(new IllegalStateException("synthetic backend failure"));

        boolean executed = SurfaceRuntime.execute(plan, null, null, null, null, false, null, brokenChunk, null, rule);

        assertFalse(executed);
        SurfaceExecutionPlan quarantined = SurfaceRuntime.prepare(rule);
        assertEquals(SurfaceTier.VANILLA_CLEAN_PATH, quarantined.tier());
        assertEquals(SurfaceCommitMode.VANILLA, quarantined.commitMode());
        assertEquals(FallbackReason.QUARANTINED, quarantined.fallbackReason());
        assertEquals(1L, SurfaceMetrics.snapshot().get("quarantineEvents"));
    }

    @Test
    void sectionCopySnapshotIsResolvedBeforeCowMutation() {
        int[] raw = new int[4096];
        LevelChunkSection section = flatSection(raw);
        ChunkAccess chunk = chunk(section);
        int index = localIndex(1, 2, 3);
        raw[index] = Block.getId(Blocks.STONE.defaultBlockState());

        SurfaceReadSnapshot snapshot = new SnapshotResolver().resolve(SnapshotPlan.sectionCopyOnRead(), chunk);
        new SectionCowManager(chunk).writerForY(2).setBlockState(1, 2, 3, Blocks.DIRT.defaultBlockState());

        assertTrue(snapshot.available());
        assertEquals(Blocks.STONE, snapshot.getBlockState(1, 2, 3).getBlock());
        assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), raw[index]);
    }

    @Test
    void directWriteSupportOnlyReplacesDefaultBlocks() throws Exception {
        int[] raw = new int[4096];
        int stoneId = Block.getId(Blocks.STONE.defaultBlockState());
        int dirtId = Block.getId(Blocks.DIRT.defaultBlockState());
        int grassId = Block.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        raw[localIndex(1, 1, 1)] = stoneId;
        raw[localIndex(2, 1, 1)] = dirtId;
        LevelChunkSection section = flatSection(raw);
        when(((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class)))
                .thenAnswer(invocation -> {
                    System.arraycopy(invocation.getArgument(0), 0, raw, 0, raw.length);
                    return true;
                });
        ChunkAccess chunk = chunk(section);
        when(chunk.getHeight(eq(Heightmap.Types.WORLD_SURFACE_WG), anyInt(), anyInt())).thenReturn(1);
        SurfaceSystem surfaceSystem = surfaceSystemWithDefaultBlock(Blocks.STONE.defaultBlockState());
        SurfaceExecutionContext context = new SurfaceExecutionContext(surfaceSystem, null, null, null, false, null, chunk, null, null, null, null, null);

        assertTrue(DirectWriteSupport.fillChunkDirect(context, Blocks.GRASS_BLOCK.defaultBlockState()));

        assertEquals(grassId, raw[localIndex(1, 1, 1)]);
        assertEquals(dirtId, raw[localIndex(2, 1, 1)]);
        verify((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class));
    }

    @Test
    void directWriteSupportFailsWithoutSurfaceSystem() {
        int[] raw = new int[4096];
        LevelChunkSection section = flatSection(raw);
        ChunkAccess chunk = chunk(section);
        SurfaceExecutionContext context = new SurfaceExecutionContext(null, null, null, null, false, null, chunk, null, null, null, null, null);

        assertFalse(DirectWriteSupport.fillChunkDirect(context, Blocks.GRASS_BLOCK.defaultBlockState()));
        verify((LevelChunkSection$FlatBlockArray) section, never()).bts$copyRawBlockDataForGeneration(any(int[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hybridTierOneConstantTemplateUsesCowSpecializedPath() throws Exception {
        int[] raw = new int[4096];
        int stoneId = Block.getId(Blocks.STONE.defaultBlockState());
        int dirtId = Block.getId(Blocks.DIRT.defaultBlockState());
        int grassId = Block.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        int stoneIndex = localIndex(1, 1, 1);
        int dirtIndex = localIndex(2, 1, 1);
        raw[stoneIndex] = stoneId;
        raw[dirtIndex] = dirtId;
        LevelChunkSection section = flatSection(raw);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int localX = invocation.getArgument(0);
            int localY = invocation.getArgument(1);
            int localZ = invocation.getArgument(2);
            int id = raw[localIndex(localX, localY, localZ)];
            if (id == stoneId) {
                return Blocks.STONE.defaultBlockState();
            }
            if (id == dirtId) {
                return Blocks.DIRT.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        });
        when(((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class)))
                .thenAnswer(invocation -> {
                    System.arraycopy(invocation.getArgument(0), 0, raw, 0, raw.length);
                    return true;
                });
        ChunkAccess chunk = chunk(section);
        when(chunk.getHeight(eq(Heightmap.Types.WORLD_SURFACE_WG), anyInt(), anyInt())).thenReturn(1);
        SectionCowManager cowManager = new SectionCowManager(chunk);
        SurfaceExecutionContext context = new SurfaceExecutionContext(
                surfaceSystemWithDefaultBlock(Blocks.STONE.defaultBlockState()),
                null,
                null,
                null,
                false,
                null,
                chunk,
                null,
                null,
                null,
                null,
                cowManager
        );
        SurfaceExecutionPlan plan = hybridConstantSequencePlan();

        assertTrue(HybridKernelSupport.execute(plan, context));
        assertEquals(stoneId, raw[stoneIndex]);
        assertEquals(dirtId, raw[dirtIndex]);
        assertTrue(cowManager.dirty());

        cowManager.commit();

        assertEquals(grassId, raw[stoneIndex]);
        assertEquals(dirtId, raw[dirtIndex]);
        verify((LevelChunkSection$FlatBlockArray) section).bts$copyRawBlockDataForGeneration(any(int[].class));
        verify(section, never()).setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), eq(false));
        java.util.Map<String, Object> tier1Backend = (java.util.Map<String, Object>) SurfaceMetrics.snapshot().get("tier1Backend");
        assertEquals(1L, tier1Backend.get("specializedExecutions"));
        assertEquals(0L, tier1Backend.get("genericInterpreterExecutions"));
    }

    private static int localIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    private static ChunkAccess chunk(LevelChunkSection section) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
        when(chunk.getMinBuildHeight()).thenReturn(0);
        when(chunk.getSection(0)).thenReturn(section);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[]{section});
        for (Heightmap.Types type : Heightmap.Types.values()) {
            when(chunk.getOrCreateHeightmapUnprimed(type)).thenReturn(mock(Heightmap.class));
        }
        return chunk;
    }

    private static LevelChunkSection flatSection(int[] raw) {
        LevelChunkSection section = mock(LevelChunkSection.class,
                withSettings().extraInterfaces(LevelChunkSection$FlatBlockArray.class));
        when(section.hasOnlyAir()).thenReturn(false);
        when(((LevelChunkSection$FlatBlockArray) section).bts$getRawBlockData()).thenReturn(raw);
        return section;
    }

    private static SurfaceExecutionPlan hybridConstantSequencePlan() {
        SurfaceNode root = SurfaceNode.sequence(List.of(
                SurfaceNode.state(Blocks.GRASS_BLOCK.defaultBlockState(), "test.hybrid")
        ), "test.hybrid.sequence");
        SurfaceProgramIr ir = new SurfaceProgramIr("test.hybrid.sequence", root);
        SurfaceStateToken t0 = SurfaceStateToken.initial();
        SurfaceStateToken t1 = t0.next();
        ir.add(new SurfaceOp("SEQUENCE", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.CONSTANT, t0, t1, "children=1"));
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:grass_block"));
        SurfaceFacts facts = new SurfaceFacts(true, true, false, false, true, true, true, ir.ops().size(), 1,
                Set.of(SurfaceDomain.CONSTANT.name()), SnapshotPlan.none(), HaloPlan.none());
        FingerprintCacheKey key = new FingerprintCacheKey("hybrid-constant", "mc", "ga", 0L, "adapters", "runtime", "profile", "safe");
        return new SurfaceExecutionPlan(key, SurfaceTier.GUARDED_HYBRID_JIT, SurfaceCommitMode.COW_SHADOW, ir, facts, FallbackReason.UNCERTIFIED);
    }

    private static SurfaceSystem surfaceSystemWithDefaultBlock(BlockState defaultBlock) throws Exception {
        SurfaceSystem surfaceSystem = mock(SurfaceSystem.class);
        Field field = SurfaceSystem.class.getDeclaredField("defaultBlock");
        field.setAccessible(true);
        field.set(surfaceSystem, defaultBlock);
        return surfaceSystem;
    }
}
