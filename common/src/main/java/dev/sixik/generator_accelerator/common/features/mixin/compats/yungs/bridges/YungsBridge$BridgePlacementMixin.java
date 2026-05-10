package dev.sixik.generator_accelerator.common.features.mixin.compats.yungs.bridges;

import com.yungnickyoung.minecraft.yungsbridges.world.placement.BridgePlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BridgePlacement.class)
public abstract class YungsBridge$BridgePlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Final
    @Shadow public int length;
    @Final
    @Shadow public int width;
    @Final
    @Shadow public int minWaterZ;
    @Final
    @Shadow public int maxWaterZ;
    @Shadow public int widthOffset;
    @Shadow public int numSolidBlocksNeeded;
    @Shadow public boolean isZAxis;

    @Unique
    private static final int[] bts$DIRECTIONS = new int[]{-1, 1};

    @Unique
    private static final ThreadLocal<GA$BridgeScratch> GA$SCRATCH =
            ThreadLocal.withInitial(GA$BridgeScratch::new);

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int baseX = BlockPos.getX(packedPos);
        int baseZ = BlockPos.getZ(packedPos);

        int seaLevel = context.getLevel().getSeaLevel() - 1;
        GA$BridgeScratch scratch = GA$SCRATCH.get();
        BlockPos.MutableBlockPos mutPos = scratch.mutPos;
        BlockPos.MutableBlockPos startPos = scratch.startPos;
        BlockPos.MutableBlockPos endPos = scratch.endPos;

        int halfWidth = this.width / 2;

        for (int candidateMiddleMinorAxisOffset = halfWidth + this.widthOffset + 1; candidateMiddleMinorAxisOffset < 16 - halfWidth - this.widthOffset; ++candidateMiddleMinorAxisOffset) {
            for (int candidateStartMajorAxisOffset = 0; candidateStartMajorAxisOffset < 16; ++candidateStartMajorAxisOffset) {

                if (this.isZAxis) {
                    startPos.set(baseX + candidateMiddleMinorAxisOffset, seaLevel, baseZ + candidateStartMajorAxisOffset);
                    endPos.set(baseX + candidateMiddleMinorAxisOffset, seaLevel, baseZ + candidateStartMajorAxisOffset + this.length + 1);
                } else {
                    startPos.set(baseX + candidateStartMajorAxisOffset, seaLevel, baseZ + candidateMiddleMinorAxisOffset);
                    endPos.set(baseX + candidateStartMajorAxisOffset + this.length + 1, seaLevel, baseZ + candidateMiddleMinorAxisOffset);
                }

                if (context.getBlockState(startPos).canOcclude()
                        && context.getBlockState(endPos).canOcclude()
                        && context.getHeight(Heightmap.Types.WORLD_SURFACE, startPos.getX(), startPos.getZ()) <= seaLevel + 1
                        && context.getHeight(Heightmap.Types.WORLD_SURFACE, endPos.getX(), endPos.getZ()) <= seaLevel + 1) {

                    int numSolidBlocks = 1;

                    for (int i = 0; i < bts$DIRECTIONS.length; i++) {
                        int direction = bts$DIRECTIONS[i];
                        for (int minorAxisSolidDist = 1; minorAxisSolidDist <= halfWidth; ++minorAxisSolidDist) {
                            int minorAxisSolidOffset = direction * minorAxisSolidDist;
                            if (this.isZAxis) {
                                mutPos.set(startPos.getX() + minorAxisSolidOffset, startPos.getY(), startPos.getZ());
                            } else {
                                mutPos.set(startPos.getX(), startPos.getY(), startPos.getZ() + minorAxisSolidOffset);
                            }

                            if (!context.getBlockState(mutPos).canOcclude() || context.getHeight(Heightmap.Types.WORLD_SURFACE, mutPos.getX(), mutPos.getZ()) > seaLevel + 1) {
                                break;
                            }
                            ++numSolidBlocks;
                        }
                    }

                    if (numSolidBlocks >= this.numSolidBlocksNeeded) {
                        numSolidBlocks = 1;

                        for (int i = 0; i < bts$DIRECTIONS.length; i++) {
                            int direction = bts$DIRECTIONS[i];
                            for (int minorAxisSolidDist = 1; minorAxisSolidDist <= halfWidth; ++minorAxisSolidDist) {
                                int minorAxisSolidOffset = direction * minorAxisSolidDist;
                                if (this.isZAxis) {
                                    mutPos.set(endPos.getX() + minorAxisSolidOffset, endPos.getY(), endPos.getZ());
                                } else {
                                    mutPos.set(endPos.getX(), endPos.getY(), endPos.getZ() + minorAxisSolidOffset);
                                }

                                if (!context.getBlockState(mutPos).canOcclude() || context.getHeight(Heightmap.Types.WORLD_SURFACE, mutPos.getX(), mutPos.getZ()) > seaLevel + 1) {
                                    break;
                                }
                                ++numSolidBlocks;
                            }
                        }

                        if (numSolidBlocks >= this.numSolidBlocksNeeded) {
                            boolean isAllWater = true;

                            for (int minorAxisWaterOffset = -halfWidth; minorAxisWaterOffset <= halfWidth; ++minorAxisWaterOffset) {
                                for (int majorAxisWaterOffset = this.minWaterZ; majorAxisWaterOffset <= this.maxWaterZ; ++majorAxisWaterOffset) {
                                    if (this.isZAxis) {
                                        mutPos.set(startPos.getX() + minorAxisWaterOffset, seaLevel, startPos.getZ() + majorAxisWaterOffset);
                                    } else {
                                        mutPos.set(startPos.getX() + majorAxisWaterOffset, seaLevel, startPos.getZ() + minorAxisWaterOffset);
                                    }

                                    if (!context.getBlockState(mutPos).getFluidState().isSource()) {
                                        if(!context.getBlockState(mutPos).liquid()) {
                                            isAllWater = false;
                                            break;
                                        }
                                    }
                                }
                                if (!isAllWater) break;
                            }

                            if (isAllWater) {
                                long finalPacked;
                                if (this.isZAxis) {
                                    finalPacked = BlockPos.asLong(startPos.getX() - halfWidth - this.widthOffset, seaLevel, startPos.getZ() + 1);
                                } else {
                                    finalPacked = BlockPos.asLong(startPos.getX() + 1, seaLevel, startPos.getZ() + halfWidth + this.widthOffset);
                                }

                                output.add(finalPacked);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    @Unique
    private static final class GA$BridgeScratch {
        final BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos startPos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos endPos = new BlockPos.MutableBlockPos();
    }
}
