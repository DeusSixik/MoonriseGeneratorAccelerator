package dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures;

import com.telepathicgrunt.repurposedstructures.world.placements.SnapToLowerNonAirPlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SnapToLowerNonAirPlacement.class)
public abstract class Repurposedstructures$SnapToLowerNonAirPlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int x = BlockPos.getX(packedPos);
        int startY = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);
        int minY = context.getMinGenY();

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int localX = x & 15;
        int localZ = z & 15;

        ChunkAccess chunk = context.getLevel().getChunk(chunkX, chunkZ);
        LevelChunkSection[] sections = chunk.getSections();

        int currentY = startY;
        int sectionIndex = chunk.getSectionIndex(currentY);

        net.minecraft.world.level.chunk.LevelChunkSection currentSection =
                (sectionIndex >= 0 && sectionIndex < sections.length) ? sections[sectionIndex] : null;

        while (currentY > minY) {
            int currentSectionIdx = chunk.getSectionIndex(currentY);

            if (currentSectionIdx != sectionIndex) {
                sectionIndex = currentSectionIdx;
                currentSection = (sectionIndex >= 0 && sectionIndex < sections.length) ? sections[sectionIndex] : null;
            }

            net.minecraft.world.level.block.state.BlockState state;
            if (currentSection != null && !currentSection.hasOnlyAir()) {
                state = currentSection.getBlockState(localX, currentY & 15, localZ);
            } else {
                state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }

            if (!state.isAir()) {
                break;
            }
            currentY--;
        }

        output.add(BlockPos.asLong(x, currentY, z));
    }
}
