package dev.sixik.generator_accelerator.common.features.mixin.compats.biomesoplenty;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "biomesoplenty.worldgen.feature.misc.WebbingFeature", remap = false)
public abstract class BiomesOPlenty$WebbingFeatureMixin {
    @Unique
    private static final Direction[] GA$DIRECTIONS = Direction.values();

    @Unique
    private static final ResourceLocation GA$WEBBING_ID = ResourceLocation.fromNamespaceAndPath("biomesoplenty", "webbing");

    @Unique
    private static final BlockState GA$STONE = Blocks.STONE.defaultBlockState();
    @Unique
    private static final BlockState GA$ANDESITE = Blocks.ANDESITE.defaultBlockState();
    @Unique
    private static final BlockState GA$DIORITE = Blocks.DIORITE.defaultBlockState();
    @Unique
    private static final BlockState GA$GRANITE = Blocks.GRANITE.defaultBlockState();
    @Unique
    private static final BlockState GA$DRIPSTONE_BLOCK = Blocks.DRIPSTONE_BLOCK.defaultBlockState();
    @Unique
    private static final BlockState GA$CALCITE = Blocks.CALCITE.defaultBlockState();
    @Unique
    private static final BlockState GA$TUFF = Blocks.TUFF.defaultBlockState();
    @Unique
    private static final BlockState GA$DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

    @Unique
    private static volatile BlockState[] GA$WEBBING_BY_MASK;

    /**
     * @author Sixik
     * @reason Avoid BlockPos.relative allocations, Direction.values() clones, and repeated
     * BlockState.setValue chains in Biomes O' Plenty cave webbing.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        BlockPos origin = context.origin();
        int placed = 0;
        int radius = random.nextInt(6) + 2;
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();
        int radiusSqr = radius * radius;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();

        for (int x = originX - radius; x <= originX + radius; x++) {
            for (int z = originZ - radius; z <= originZ + radius; z++) {
                int dx = x - originX;
                int dz = z - originZ;
                if (dx * dx + dz * dz > radiusSqr) {
                    continue;
                }

                for (int y = originY - radius; y <= originY + radius; y++) {
                    pos.set(x, y, z);
                    BlockState current = level.getBlockState(pos);
                    int faceMask = 0;
                    for (Direction direction : GA$DIRECTIONS) {
                        neighbor.setWithOffset(pos, direction);
                        if (ga$isWebbingSupport(level.getBlockState(neighbor))) {
                            faceMask |= 1 << direction.ordinal();
                        }
                    }

                    if (current.isAir() && faceMask != 0) {
                        level.setBlock(pos, ga$webbingState(faceMask), 2);
                        placed++;
                        break;
                    }
                }
            }
        }

        return placed > 0;
    }

    @Unique
    private static boolean ga$isWebbingSupport(BlockState state) {
        return state == GA$STONE
                || state == GA$ANDESITE
                || state == GA$DIORITE
                || state == GA$GRANITE
                || state == GA$DRIPSTONE_BLOCK
                || state == GA$CALCITE
                || state == GA$TUFF
                || state == GA$DEEPSLATE;
    }

    @Unique
    private static BlockState ga$webbingState(int faceMask) {
        BlockState[] states = GA$WEBBING_BY_MASK;
        if (states == null) {
            states = ga$buildWebbingStates();
            GA$WEBBING_BY_MASK = states;
        }
        return states[faceMask];
    }

    @Unique
    private static BlockState[] ga$buildWebbingStates() {
        BlockState base = BuiltInRegistries.BLOCK.get(GA$WEBBING_ID).defaultBlockState();
        BlockState[] states = new BlockState[1 << GA$DIRECTIONS.length];
        for (int mask = 0; mask < states.length; mask++) {
            BlockState state = base;
            for (Direction direction : GA$DIRECTIONS) {
                if ((mask & (1 << direction.ordinal())) != 0) {
                    state = state.setValue(MultifaceBlock.getFaceProperty(direction), Boolean.TRUE);
                }
            }
            states[mask] = state;
        }
        return states;
    }
}
