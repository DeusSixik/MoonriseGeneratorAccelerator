package dev.sixik.generator_accelerator.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Map;

@Mixin(value = FluidInteractionRegistry.class, remap = false)
public abstract class MixinFluidInteractionRegistry$fast_can_interact {
    @Unique
    private static final Direction[] GA$INTERACTION_DIRECTIONS = {
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SIDE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    @Final
    private static Map<FluidType, List<FluidInteractionRegistry.InteractionInformation>> INTERACTIONS;

    /**
     * @author Sixik
     * @reason NeoForge allocates an ArrayList iterator for every checked fluid side.
     * In water-heavy modded biomes this becomes allocation pressure during neighbor updates.
     */
    @Overwrite(remap = false)
    public static boolean canInteract(Level level, BlockPos currentPos) {
        FluidState currentFluid = level.getFluidState(currentPos);
        List<FluidInteractionRegistry.InteractionInformation> interactions =
                INTERACTIONS.get(currentFluid.getFluidType());
        if (interactions == null || interactions.isEmpty()) {
            return false;
        }

        BlockPos.MutableBlockPos sidePos = GA$SIDE_POS.get();
        for (int directionIndex = 0; directionIndex < GA$INTERACTION_DIRECTIONS.length; directionIndex++) {
            sidePos.setWithOffset(currentPos, GA$INTERACTION_DIRECTIONS[directionIndex]);
            int interactionCount = interactions.size();
            for (int i = 0; i < interactionCount; i++) {
                FluidInteractionRegistry.InteractionInformation interaction = interactions.get(i);
                if (interaction.predicate().test(level, currentPos, sidePos, currentFluid)) {
                    interaction.interaction().interact(level, currentPos, sidePos.immutable(), currentFluid);
                    return true;
                }
            }
        }
        return false;
    }
}
