package dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode;

import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceMetrics;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.interpreter.MaskInterpreterBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceNode;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionContext;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceSystem;

import java.lang.reflect.Field;

/** Stable runtime helper used by generated Tier 1 hybrid kernels. */
public final class HybridKernelSupport {
    private static final MaskInterpreterBackend INTERPRETER = new MaskInterpreterBackend();
    private static final Field DEFAULT_BLOCK = defaultBlockField();

    private HybridKernelSupport() {
    }

    public static boolean execute(SurfaceExecutionPlan plan, SurfaceExecutionContext context) {
        if (plan == null || context == null) {
            return false;
        }
        if (executeConstantCowTemplate(plan, context)) {
            SurfaceMetrics.tier1SpecializedExecution();
            return true;
        }
        SurfaceMetrics.tier1GenericInterpreterExecution();
        return INTERPRETER.execute(plan, context);
    }

    private static boolean executeConstantCowTemplate(SurfaceExecutionPlan plan, SurfaceExecutionContext context) {
        BlockState state = constantState(plan);
        if (state == null || context.chunk() == null || context.surfaceSystem() == null || context.cowManager() == null) {
            return false;
        }
        BlockState defaultBlock = defaultBlock(context.surfaceSystem());
        if (defaultBlock == null) {
            return false;
        }
        ChunkAccess chunk = context.chunk();
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return false;
        }

        ChunkPos chunkPos = chunk.getPos();
        if (chunkPos == null) {
            return false;
        }
        int minBuildY = chunk.getMinBuildHeight();
        int maxY = minBuildY + (sections.length << 4) - 1;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int topY = Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) + 1);
                for (int y = minBuildY; y <= topY; y++) {
                    int sectionIndex = (y - minBuildY) >> 4;
                    if (sectionIndex < 0 || sectionIndex >= sections.length) {
                        continue;
                    }
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null) {
                        return false;
                    }
                    int localY = y & 15;
                    BlockState current = section.getBlockState(localX, localY, localZ);
                    if (current == defaultBlock || current.is(defaultBlock.getBlock())) {
                        context.cowManager().writerForSection(sectionIndex).setBlockState(localX, localY, localZ, state);
                    }
                }
            }
        }
        return true;
    }

    private static BlockState constantState(SurfaceExecutionPlan plan) {
        if (plan == null || plan.ir() == null || plan.ir().root() == null) {
            return null;
        }
        SurfaceNode root = plan.ir().root();
        if (root.kind() == SurfaceNode.Kind.STATE) {
            return root.blockState();
        }
        if (root.kind() == SurfaceNode.Kind.SEQUENCE && root.children().size() == 1) {
            SurfaceNode child = root.children().get(0);
            return child.kind() == SurfaceNode.Kind.STATE ? child.blockState() : null;
        }
        return null;
    }

    private static BlockState defaultBlock(SurfaceSystem surfaceSystem) {
        if (DEFAULT_BLOCK == null) {
            return null;
        }
        try {
            return (BlockState) DEFAULT_BLOCK.get(surfaceSystem);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Field defaultBlockField() {
        try {
            Field field = SurfaceSystem.class.getDeclaredField("defaultBlock");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
