package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.cell.Cell;
import com.dtteam.dynamictrees.systems.cell.CellKits;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "com.dtteam.dynamictrees.systems.cell.CellKits$BasicSolver", remap = false)
public abstract class DynamicTrees$CellKits$BasicSolverMixin {
    @Shadow
    @Final
    private short[] codes;

    @Unique
    private static final Direction[] GA$DIRECTIONS = Direction.values();

    @Unique
    private static final ThreadLocal<int[]> GA$COUNTS =
            ThreadLocal.withInitial(() -> new int[16]);

    /**
     * @author Sixik
     * @reason Dynamic Trees solves leaf hydration thousands of times during
     * worldgen. Reuse the tiny counter array and avoid Direction.values clones.
     */
    @Overwrite(remap = false)
    public int solve(Cell[] cells) {
        int[] counts = GA$COUNTS.get();
        for (int i = 0; i < 16; i++) {
            counts[i] = 0;
        }

        for (int i = 0; i < GA$DIRECTIONS.length; i++) {
            Direction dir = GA$DIRECTIONS[i];
            counts[cells[dir.ordinal()].getValueFromSide(dir.getOpposite())]++;
        }
        return CellKits.solveCell(counts, this.codes);
    }
}
