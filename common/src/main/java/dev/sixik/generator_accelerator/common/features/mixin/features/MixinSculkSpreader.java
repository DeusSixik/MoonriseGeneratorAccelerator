package dev.sixik.generator_accelerator.common.features.mixin.features;

import dev.sixik.generator_accelerator.common.features.SculkSpreaderCursorScratch;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SculkSpreader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Mixin(value = SculkSpreader.class, priority = 999)
public abstract class MixinSculkSpreader {
    @Shadow
    private List<SculkSpreader.ChargeCursor> cursors;

    @Shadow
    public abstract boolean isWorldGeneration();

    @Unique
    private static final ThreadLocal<SculkSpreaderCursorScratch> GA$CURSOR_UPDATE_SCRATCH =
            ThreadLocal.withInitial(SculkSpreaderCursorScratch::new);

    /**
     * @author Sixik
     * @reason Avoid per-attempt HashMap/list allocation in worldgen sculk patches and remove lambda overhead elsewhere.
     */
    @Overwrite
    public void updateCursors(LevelAccessor level, BlockPos origin, RandomSource random, boolean shouldSpread) {
        List<SculkSpreader.ChargeCursor> current = this.cursors;
        if (current.isEmpty()) {
            return;
        }

        if (this.isWorldGeneration() && level instanceof WorldGenRegion) {
            updateWorldgenCursorsInPlaceNoEvents(level, origin, random, (SculkSpreader) (Object) this, shouldSpread, current);
            return;
        }

        SculkSpreaderCursorScratch scratch = GA$CURSOR_UPDATE_SCRATCH.get();
        Object2ObjectOpenHashMap<BlockPos, SculkSpreader.ChargeCursor> cursorByPos = scratch.cursorByPos;
        Object2IntOpenHashMap<BlockPos> chargeByPos = scratch.chargeByPos;
        cursorByPos.clear();
        chargeByPos.clear();

        if (this.isWorldGeneration()) {
            updateWorldgenCursorsInPlace(level, origin, random, (SculkSpreader) (Object) this, shouldSpread, current, cursorByPos, chargeByPos);
            emitChargeEvents(level, cursorByPos, chargeByPos);
            return;
        }

        ObjectArrayList<SculkSpreader.ChargeCursor> next = new ObjectArrayList<>(current.size());
        for (int i = 0, size = current.size(); i < size; i++) {
            SculkSpreader.ChargeCursor cursor = current.get(i);
            cursor.update(level, origin, random, (SculkSpreader) (Object) this, shouldSpread);
            int charge = cursor.getCharge();
            if (charge <= 0) {
                level.levelEvent(3006, cursor.getPos(), 0);
                continue;
            }

            BlockPos pos = cursor.getPos();
            chargeByPos.addTo(pos, charge);
            SculkSpreader.ChargeCursor existing = cursorByPos.get(pos);
            if (existing == null) {
                cursorByPos.put(pos, cursor);
                next.add(cursor);
                continue;
            }

            if (charge + existing.getCharge() <= 1000) {
                ((MixinSculkSpreaderChargeCursorAccess) (Object) existing).ga$mergeWith(cursor);
                continue;
            }

            next.add(cursor);
            if (charge < existing.getCharge()) {
                cursorByPos.put(pos, cursor);
            }
        }

        emitChargeEvents(level, cursorByPos, chargeByPos);
        this.cursors = next;
    }

    @Unique
    private static void updateWorldgenCursorsInPlaceNoEvents(
            LevelAccessor level,
            BlockPos origin,
            RandomSource random,
            SculkSpreader spreader,
            boolean shouldSpread,
            List<SculkSpreader.ChargeCursor> cursors
    ) {
        int writeIndex = 0;
        int size = cursors.size();
        for (int i = 0; i < size; i++) {
            SculkSpreader.ChargeCursor cursor = cursors.get(i);
            cursor.update(level, origin, random, spreader, shouldSpread);
            if (cursor.getCharge() <= 0) {
                continue;
            }

            if (writeIndex != i) {
                cursors.set(writeIndex, cursor);
            }
            writeIndex++;
        }

        for (int i = size - 1; i >= writeIndex; i--) {
            cursors.remove(i);
        }
    }

    @Unique
    private static void updateWorldgenCursorsInPlace(
            LevelAccessor level,
            BlockPos origin,
            RandomSource random,
            SculkSpreader spreader,
            boolean shouldSpread,
            List<SculkSpreader.ChargeCursor> cursors,
            Object2ObjectOpenHashMap<BlockPos, SculkSpreader.ChargeCursor> cursorByPos,
            Object2IntOpenHashMap<BlockPos> chargeByPos
    ) {
        int writeIndex = 0;
        int size = cursors.size();
        for (int i = 0; i < size; i++) {
            SculkSpreader.ChargeCursor cursor = cursors.get(i);
            cursor.update(level, origin, random, spreader, shouldSpread);
            int charge = cursor.getCharge();
            if (charge <= 0) {
                level.levelEvent(3006, cursor.getPos(), 0);
                continue;
            }

            BlockPos pos = cursor.getPos();
            chargeByPos.addTo(pos, charge);
            SculkSpreader.ChargeCursor existing = cursorByPos.get(pos);
            if (existing == null || charge < existing.getCharge()) {
                cursorByPos.put(pos, cursor);
            }

            if (writeIndex != i) {
                cursors.set(writeIndex, cursor);
            }
            writeIndex++;
        }

        for (int i = size - 1; i >= writeIndex; i--) {
            cursors.remove(i);
        }
    }

    @Unique
    private static void emitChargeEvents(
            LevelAccessor level,
            Object2ObjectOpenHashMap<BlockPos, SculkSpreader.ChargeCursor> cursorByPos,
            Object2IntOpenHashMap<BlockPos> chargeByPos
    ) {
        for (Object2IntMap.Entry<BlockPos> entry : chargeByPos.object2IntEntrySet()) {
            BlockPos pos = entry.getKey();
            int charge = entry.getIntValue();
            SculkSpreader.ChargeCursor cursor = cursorByPos.get(pos);
            Set<Direction> facings = cursor == null ? null : cursor.getFacingData();
            if (charge <= 0 || facings == null) {
                continue;
            }

            int power = (int) (Math.log1p(charge) / (double) 2.3F) + 1;
            int eventData = (power << 6) + MultifaceBlock.pack(facings);
            level.levelEvent(3006, pos, eventData);
        }
    }

}
