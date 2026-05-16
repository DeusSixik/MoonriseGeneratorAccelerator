package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.treedata.TreePart;
import com.dtteam.dynamictrees.api.voxmap.SimpleVoxmap;
import com.dtteam.dynamictrees.block.branch.BranchBlock;
import com.dtteam.dynamictrees.block.soil.SoilBlock;
import com.dtteam.dynamictrees.systems.nodemapper.InflatorNode;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.tree.species.Species;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = InflatorNode.class, remap = false)
public abstract class DynamicTrees$InflatorNodeMixin {
    @Shadow
    private float radius;

    @Shadow
    private BlockPos last;

    @Shadow
    private BlockPos highestTrunkBlock;

    @Shadow
    private int maxRadius;

    @Shadow
    Species species;

    @Shadow
    SimpleVoxmap leafMap;

    @Unique
    private static final Direction[] GA$DIRECTIONS = Direction.values();

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Avoid the temporary pos.above() allocation while scanning the
     * Dynamic Trees branch network.
     */
    @Overwrite(remap = false)
    public boolean run(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) {
        BranchBlock branch = TreeHelper.getBranch(state);
        if (branch != null) {
            this.radius = this.species.getFamily().getPrimaryThickness();
            if (this.highestTrunkBlock == null) {
                BlockPos.MutableBlockPos above = GA$MUTABLE_POS.get();
                above.set(pos.getX(), pos.getY() + 1, pos.getZ());
                if (!TreeHelper.isBranch(level.getBlockState(above))) {
                    this.highestTrunkBlock = pos;
                }
            }
        }
        return false;
    }

    /**
     * @author Sixik
     * @reason Reuse neighbor coordinates and cached directions during leaf-map
     * inflation; this path is hit recursively for every generated Dynamic Tree.
     */
    @Overwrite(remap = false)
    public boolean returnRun(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) {
        BranchBlock branch = TreeHelper.getBranch(state);
        if (branch != null) {
            float areaAccum = this.radius * this.radius;
            boolean isTwig = true;
            BlockPos.MutableBlockPos deltaPos = GA$MUTABLE_POS.get();
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();

            for (int i = 0; i < GA$DIRECTIONS.length; i++) {
                Direction dir = GA$DIRECTIONS[i];
                if (dir == fromDir) {
                    continue;
                }

                deltaPos.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                if (deltaPos.equals(this.last)) {
                    isTwig = false;
                    continue;
                }

                BlockState deltaBlockState = level.getBlockState(deltaPos);
                TreePart treePart = TreeHelper.getTreePart(deltaBlockState);
                if (branch.isSameTree(treePart)) {
                    int branchRadius = treePart.getRadius(deltaBlockState);
                    areaAccum += branchRadius * branchRadius;
                }
            }

            if (isTwig) {
                if (this.leafMap != null) {
                    this.leafMap.setVoxel(pos, (byte) 16);
                    SimpleVoxmap leafCluster = this.species.getLeavesProperties().getCellKit().getLeafCluster();
                    this.leafMap.blitMax(pos, leafCluster);
                }
            } else {
                this.radius = (float) Math.sqrt(areaAccum)
                        + this.species.getTapering() * this.species.getWorldGenTaperingFactor();
                if (this.radius > this.maxRadius) {
                    this.radius = this.maxRadius;
                }
                if (this.highestTrunkBlock != null) {
                    boolean isInTrunk = x == this.highestTrunkBlock.getX()
                            && y <= this.highestTrunkBlock.getY()
                            && z == this.highestTrunkBlock.getZ();
                    if (this.radius > 8.0F && !isInTrunk) {
                        this.radius = 8.0F;
                    }
                }

                float secondaryThickness = this.species.getFamily().getSecondaryThickness();
                if (this.radius < secondaryThickness) {
                    this.radius = secondaryThickness;
                }
                branch.setRadius(level, pos, (int) Math.floor(this.radius), null);
                if (this.leafMap != null) {
                    this.leafMap.setVoxel(pos, (byte) 32);
                }
            }
            this.last = pos;
        } else {
            SoilBlock rooty = TreeHelper.getRooty(state);
            if (rooty != null) {
                rooty.updateRadius(level, state, pos, 2, false);
            }
        }
        return false;
    }
}
