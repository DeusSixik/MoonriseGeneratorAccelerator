package dev.sixik.generator_accelerator.common.features.mixin;

import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.BiConsumer;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess$fast_block_light_sources {

    @Shadow
    @Final
    protected ChunkPos chunkPos;

    @Shadow
    @Final
    protected LevelHeightAccessor levelHeightAccessor;

    @Shadow
    @Final
    protected LevelChunkSection[] sections;

    /**
     * @author Sixik
     * @reason Avoid repeated dynamic light-emission predicate calls while lighting freshly generated raw sections.
     */
    @Overwrite
    public final void findBlockLightSources(BiConsumer<BlockPos, BlockState> consumer) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int minSection = this.levelHeightAccessor.getMinSection();
        int chunkBaseX = this.chunkPos.x << 4;
        int chunkBaseZ = this.chunkPos.z << 4;

        for (int sectionIndex = 0; sectionIndex < this.sections.length; sectionIndex++) {
            int sectionY = minSection + sectionIndex;
            LevelChunkSection section = this.sections[sectionIndex];
            boolean mayEmitLight = section instanceof LevelChunkSection$FlatBlockArray flatBlockArray
                    ? flatBlockArray.bts$maybeHasLightEmission()
                    : section.getStates().maybeHas(state -> state.getLightEmission() != 0);
            if (!mayEmitLight) {
                continue;
            }

            int[] raw = LevelChunkSection$FlatBlockArray.rawData(section);
            int baseY = SectionPos.sectionToBlockCoord(sectionY);
            if (raw != null) {
                for (int index = 0; index < raw.length; index++) {
                    int stateId = raw[index];
                    if (!FastBlockStateCache.hasLightEmission(stateId)) {
                        continue;
                    }
                    BlockState state = FastBlockStateCache.getBlockState(stateId);
                    consumer.accept(
                            mutable.set(chunkBaseX + (index & 15), baseY + ((index >>> 8) & 15), chunkBaseZ + ((index >>> 4) & 15)),
                            state
                    );
                }
                continue;
            }

            for (int y = 0; y < 16; y++) {
                int blockY = baseY + y;
                for (int z = 0; z < 16; z++) {
                    int blockZ = chunkBaseZ + z;
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.getLightEmission() != 0) {
                            consumer.accept(mutable.set(chunkBaseX + x, blockY, blockZ), state);
                        }
                    }
                }
            }
        }
    }
}
