package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$FixedPlacementAccess;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import dev.sixik.generator_accelerator_native_raw.memory.BlockPosPackedMemory;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.FixedPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.sixik.javastructg.structs.arrays.NativeObjectArray;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.ref.Cleaner;
import java.util.List;

@Mixin(FixedPlacement.class)
public abstract class MixinFixedPlacement extends PlacementModifier implements GA$PlacementModifierExtension, GA$FixedPlacementAccess {

    @Unique
    private static final Cleaner GA$NATIVE_CLEANER = Cleaner.create();

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$NATIVE_POS_BUFFER =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Mutable
    @Shadow
    @Final
    private List<BlockPos> positions;

    @Unique
    private NativeObjectArray<BlockPos> ga$nativePositions;

    @Unique
    private Cleaner.Cleanable ga$nativePositionsCleanable;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$init(List<BlockPos> list, CallbackInfo ci) {
        ObjectArrayList<BlockPos> copied = new ObjectArrayList<>(list);
        this.positions = copied;
        this.ga$buildNativePositions(copied);
        GeneratorAccelerator.LOGGER_DEBUG.info("Create FixedPlacement size: {}", copied.size());
    }

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public List<BlockPos> ga$fixedPositions() {
        return this.positions;
    }

    @Override
    public NativeObjectArray<BlockPos> ga$nativePositions() {
        return this.ga$nativePositions;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int chunkX = SectionPos.blockToSectionCoord(BlockPos.getX(packedPos));
        int chunkZ = SectionPos.blockToSectionCoord(BlockPos.getZ(packedPos));

        NativeObjectArray<BlockPos> nativePositions = this.ga$nativePositions;
        if (nativePositions != null) {
            BlockPos.MutableBlockPos mutable = GA$NATIVE_POS_BUFFER.get();
            int size = nativePositions.size();

            for (int i = 0; i < size; i++) {
                nativePositions.get(i, mutable);

                if (chunkX == SectionPos.blockToSectionCoord(mutable.getX())
                        && chunkZ == SectionPos.blockToSectionCoord(mutable.getZ())) {
                    output.add(mutable.asLong());
                }
            }
            return;
        }

        final ObjectArrayList<BlockPos> list = (ObjectArrayList<BlockPos>) this.positions;
        final Object[] array = list.elements();

        for (int i = 0; i < list.size(); i++) {
            BlockPos pos = (BlockPos) array[i];

            if (chunkX == SectionPos.blockToSectionCoord(pos.getX()) &&
                    chunkZ == SectionPos.blockToSectionCoord(pos.getZ())) {
                output.add(pos.asLong());
            }
        }
    }

    @Unique
    private void ga$buildNativePositions(ObjectArrayList<BlockPos> copied) {
        if (this.ga$nativePositionsCleanable != null) {
            this.ga$nativePositionsCleanable.clean();
            this.ga$nativePositionsCleanable = null;
            this.ga$nativePositions = null;
        }

        NativeObjectArray<BlockPos> nativePositions =
                new NativeObjectArray<>(Math.max(1, copied.size()), BlockPosPackedMemory.MEMORY);

        try {
            Object[] elements = copied.elements();
            for (int i = 0; i < copied.size(); i++) {
                nativePositions.add((BlockPos) elements[i]);
            }
        } catch (RuntimeException | Error failure) {
            nativePositions.freeMemory();
            throw failure;
        }

        this.ga$nativePositions = nativePositions;
        // FixedPlacement instances live in registries for a long time, so keep native storage
        // attached to the placement and let Cleaner release it when the object becomes unreachable.
        this.ga$nativePositionsCleanable = GA$NATIVE_CLEANER.register(this, nativePositions::freeMemory);
    }
}
