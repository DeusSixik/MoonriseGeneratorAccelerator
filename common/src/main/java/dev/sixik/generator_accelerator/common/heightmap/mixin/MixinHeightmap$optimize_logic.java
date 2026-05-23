package dev.sixik.generator_accelerator.common.heightmap.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.patches.GA$LevelChunkSectionExtern;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.function.Predicate;

@Mixin(Heightmap.class)
public abstract class MixinHeightmap$optimize_logic {
    @Shadow
    @Final private ChunkAccess chunk;
    @Shadow @Final
    private Predicate<BlockState> isOpaque;
    @Shadow @Final private BitStorage data;

    @Shadow private static int getIndex(int x, int z) { return 0; }

    @Unique
    private int ga$minBuildHeight;
    @Unique
    private byte ga$typeOrdinal;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga$cacheMinBuildHeight(ChunkAccess chunk, Heightmap.Types type, CallbackInfo ci) {
        this.ga$minBuildHeight = chunk.getMinBuildHeight();
        this.ga$typeOrdinal = (byte) type.ordinal();
    }

    /**
     * @author Sixik
     * @reason Optimize "Hole Punching" by skipping empty sections during downward scan.
     */
    @Overwrite
    public boolean update(int x, int y, int z, BlockState state) {
        int index = getIndex(x, z);
        int minY = this.ga$minBuildHeight;
        int currentTop = this.data.get(index) + minY;

        if (y <= currentTop - 2) return false;

        if (this.ga$isOpaque(GA$BlockStateExtension.get(state).bts$getFastId())) {
            if (y >= currentTop) {
                this.data.set(index, y + 1 - minY);
                return true;
            }
            return false;
        }

        if (currentTop - 1 != y) return false;

        for (int searchY = y - 1; searchY >= minY; searchY--) {
            int sectionIndex = this.chunk.getSectionIndex(searchY);
            LevelChunkSection section = this.chunk.getSection(sectionIndex);

            if (section.hasOnlyAir()) {
                searchY = this.chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                continue;
            }

            GA$LevelChunkSectionExtern sectionExtern = (GA$LevelChunkSectionExtern) section;

            int sectionBottom = this.chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            for (; searchY >= sectionBottom; searchY--) {
                int block = sectionExtern.ga$getBlockRaw(x, searchY & 15, z);
                if (this.ga$isOpaque(block)) {
                    this.data.set(index, searchY + 1 - minY);
                    return true;
                }
            }
        }

        this.data.set(index, 0);
        return true;
    }

    @Unique
    private boolean ga$isOpaque(int state) {
        return ga$isOpaque(state, ga$typeOrdinal);
    }

    @Unique
    private static boolean ga$isOpaque(int state, byte typeOrdinal) {
        return switch (typeOrdinal) {
            case 0, 1 -> !FastBlockStateCache.isAir(state); // WORLD_SURFACE(_WG)
            case 2, 3 -> FastBlockStateCache.IS_BLOCK_MOTION_STATES[state]; // OCEAN_FLOOR(_WG)
            case 4 -> FastBlockStateCache.IS_BLOCK_MOTION_STATES[state] || !FastBlockStateCache.IS_FLUID_STATES_EMPTY[state]; // MOTION_BLOCKING
            case 5 -> (FastBlockStateCache.IS_BLOCK_MOTION_STATES[state] || !FastBlockStateCache.IS_FLUID_STATES_EMPTY[state])
                    && !FastBlockStateCache.hasTag(state, FastBlockStateCache.BLOCK_LEAVES); // MOTION_BLOCKING_NO_LEAVES
            default -> throw new IllegalStateException("Unexpected value: " + typeOrdinal);
        };
    }

    /**
     * @author Sixik
     * @reason Optimized priming: Uses bitmasks instead of Lists, skips empty sections, zero allocation in loop.
     */
    @Overwrite
    public static void primeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types) {
        byte count = (byte) types.size();
        if (count == 0) return;
        if (bts$isFeatureHeightmapSet(types)) {
            bts$primeFeatureHeightmaps(chunk);
            return;
        }

        Heightmap[] maps = new Heightmap[count];

        int idx = 0;
        for (Heightmap.Types t : types) {
            maps[idx] = chunk.getOrCreateHeightmapUnprimed(t);
            idx++;
        }

        final int highestY = chunk.getHighestSectionPosition() + 16;
        final int minY = chunk.getMinBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int remaining = (1 << count) - 1;

                for (int y = highestY - 1; y >= minY; y--) {
                    int sectionIndex = chunk.getSectionIndex(y);
                    LevelChunkSection section = chunk.getSection(sectionIndex);

                    if (section.hasOnlyAir()) {
                        y = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                        continue;
                    }

                    GA$LevelChunkSectionExtern sectionExtern = (GA$LevelChunkSectionExtern) section;

                    int blockId = sectionExtern.ga$getBlockRaw(x, y & 15, z);
                    if (!FastBlockStateCache.AIR_STATES[blockId]) {
                        for (byte i = 0; i < count; i++) {

                            if ((remaining & (1 << i)) != 0 && ga$isOpaque(blockId, i)) {
                                maps[i].setHeight(x, z, y + 1);
                                remaining &= ~(1 << i);
                            }
                        }
                        if (remaining == 0) break;
                    }
                }

                if (remaining != 0) {
                    for (byte i = 0; i < count; i++) {
                        if ((remaining & (1 << i)) != 0) {
                            maps[i].setHeight(x, z, minY);
                        }
                    }
                }
            }
        }
    }

    @Unique
    private static boolean bts$isFeatureHeightmapSet(Set<Heightmap.Types> types) {
        return types.size() == 4
                && types.contains(Heightmap.Types.MOTION_BLOCKING)
                && types.contains(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)
                && types.contains(Heightmap.Types.OCEAN_FLOOR)
                && types.contains(Heightmap.Types.WORLD_SURFACE);
    }

    @Unique
    private static void bts$primeFeatureHeightmaps(ChunkAccess chunk) {
        Predicate<BlockState> motionBlockingPredicate = Heightmap.Types.MOTION_BLOCKING.isOpaque();
        Predicate<BlockState> motionBlockingNoLeavesPredicate = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
        Predicate<BlockState> oceanFloorPredicate = Heightmap.Types.OCEAN_FLOOR.isOpaque();
        Predicate<BlockState> worldSurfacePredicate = Heightmap.Types.WORLD_SURFACE.isOpaque();

        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        Heightmap motionBlockingNoLeaves = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);

        final int highestY = chunk.getHighestSectionPosition() + 16;
        final int minY = chunk.getMinBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int remaining = 0b1111;

                for (int y = highestY - 1; y >= minY; y--) {
                    int sectionIndex = chunk.getSectionIndex(y);
                    LevelChunkSection section = chunk.getSection(sectionIndex);

                    if (section.hasOnlyAir()) {
                        y = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                        continue;
                    }

                    BlockState state = section.getBlockState(x, y & 15, z);
                    if (state.isAir()) {
                        continue;
                    }

                    int height = y + 1;
                    if ((remaining & 0b0001) != 0 && motionBlockingPredicate.test(state)) {
                        motionBlocking.setHeight(x, z, height);
                        remaining &= ~0b0001;
                    }
                    if ((remaining & 0b0010) != 0 && motionBlockingNoLeavesPredicate.test(state)) {
                        motionBlockingNoLeaves.setHeight(x, z, height);
                        remaining &= ~0b0010;
                    }
                    if ((remaining & 0b0100) != 0 && oceanFloorPredicate.test(state)) {
                        oceanFloor.setHeight(x, z, height);
                        remaining &= ~0b0100;
                    }
                    if ((remaining & 0b1000) != 0 && worldSurfacePredicate.test(state)) {
                        worldSurface.setHeight(x, z, height);
                        remaining &= ~0b1000;
                    }
                    if (remaining == 0) {
                        break;
                    }
                }

                if ((remaining & 0b0001) != 0) {
                    motionBlocking.setHeight(x, z, minY);
                }
                if ((remaining & 0b0010) != 0) {
                    motionBlockingNoLeaves.setHeight(x, z, minY);
                }
                if ((remaining & 0b0100) != 0) {
                    oceanFloor.setHeight(x, z, minY);
                }
                if ((remaining & 0b1000) != 0) {
                    worldSurface.setHeight(x, z, minY);
                }
            }
        }
    }
}
