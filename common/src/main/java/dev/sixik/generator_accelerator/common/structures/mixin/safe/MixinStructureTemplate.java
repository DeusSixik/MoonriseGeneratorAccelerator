package dev.sixik.generator_accelerator.common.structures.mixin.safe;

import dev.sixik.generator_accelerator.common.structures.compat.ConfluenceStructureTemplateCompat;
import dev.sixik.generator_accelerator.common.structures.StructureBlockEntityNbtSanitizer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Mixin(value = StructureTemplate.class, priority = 997)
public abstract class MixinStructureTemplate {

    @Unique
    private final Object generator_accelerator$templateMutationLock = new Object();

    @Mutable
    @Shadow
    @Final
    private List<StructureTemplate.Palette> palettes;

    @Mutable
    @Shadow
    @Final
    private List<StructureTemplate.StructureEntityInfo> entityInfoList;

    @Shadow
    private Vec3i size;

    @Shadow
    private static void addToLists(
            StructureTemplate.StructureBlockInfo structureBlockInfo,
            List<StructureTemplate.StructureBlockInfo> solidBlocks,
            List<StructureTemplate.StructureBlockInfo> nbtBlocks,
            List<StructureTemplate.StructureBlockInfo> otherBlocks
    ) {
        throw new AssertionError();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(CallbackInfo ci) {
        this.palettes = Collections.emptyList();
        this.entityInfoList = Collections.emptyList();
    }

    /**
     * @author Sixik
     * @reason Publish immutable snapshots instead of mutating shared ArrayLists in-place.
     */
    @Overwrite
    public void fillFromWorld(Level level, BlockPos blockPos, Vec3i vec3i, boolean bl, @Nullable Block block) {
        if (vec3i.getX() < 1 || vec3i.getY() < 1 || vec3i.getZ() < 1) {
            return;
        }

        BlockPos blockPos2 = blockPos.offset(vec3i).offset(-1, -1, -1);
        ObjectArrayList<StructureTemplate.StructureBlockInfo> solidBlocks = new ObjectArrayList<>();
        ObjectArrayList<StructureTemplate.StructureBlockInfo> nbtBlocks = new ObjectArrayList<>();
        ObjectArrayList<StructureTemplate.StructureBlockInfo> otherBlocks = new ObjectArrayList<>();
        BlockPos min = new BlockPos(Math.min(blockPos.getX(), blockPos2.getX()), Math.min(blockPos.getY(), blockPos2.getY()), Math.min(blockPos.getZ(), blockPos2.getZ()));
        BlockPos max = new BlockPos(Math.max(blockPos.getX(), blockPos2.getX()), Math.max(blockPos.getY(), blockPos2.getY()), Math.max(blockPos.getZ(), blockPos2.getZ()));

        for (BlockPos worldPos : BlockPos.betweenClosed(min, max)) {
            BlockPos localPos = worldPos.subtract(min);
            BlockState blockState = level.getBlockState(worldPos);
            if (block != null && blockState.is(block)) {
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(worldPos);
            StructureTemplate.StructureBlockInfo structureBlockInfo = blockEntity != null
                    ? new StructureTemplate.StructureBlockInfo(localPos, blockState, blockEntity.saveWithId(level.registryAccess()))
                    : new StructureTemplate.StructureBlockInfo(localPos, blockState, null);
            addToLists(structureBlockInfo, solidBlocks, nbtBlocks, otherBlocks);
        }

        StructureTemplate.Palette palette = StructureTemplatePaletteInvoker.generator_accelerator$createPalette(
                generator_accelerator$buildInfoList(solidBlocks, nbtBlocks, otherBlocks)
        );
        List<StructureTemplate.StructureEntityInfo> entities = bl
                ? generator_accelerator$collectEntityList(level, min, max)
                : Collections.emptyList();

        synchronized (this.generator_accelerator$templateMutationLock) {
            this.size = vec3i;
            this.palettes = Collections.singletonList(palette);
            this.entityInfoList = entities;
        }
    }

    /**
     * @author Sixik
     * @reason Build the template off-thread/local-list first and publish a completed snapshot.
     */
    @Overwrite
    public void load(HolderGetter<Block> holderGetter, CompoundTag compoundTag) {
        final ListTag listTag = compoundTag.getList(StructureTemplate.SIZE_TAG, 3);
        final Vec3i loadedSize = new Vec3i(listTag.getInt(0), listTag.getInt(1), listTag.getInt(2));
        final ListTag listTag2 = compoundTag.getList(StructureTemplate.BLOCKS_TAG, 10);
        final ObjectArrayList<StructureTemplate.Palette> loadedPalettes = new ObjectArrayList<>();

        if (compoundTag.contains("palettes", 9)) {
            ListTag listTag3 = compoundTag.getList("palettes", 9);
            loadedPalettes.ensureCapacity(listTag3.size());

            for(int i = 0; i < listTag3.size(); ++i) {
                loadedPalettes.add(generator_accelerator$loadPalette(holderGetter, listTag3.getList(i), listTag2));
            }
        } else {
            loadedPalettes.add(generator_accelerator$loadPalette(holderGetter, compoundTag.getList("palette", 10), listTag2));
        }


        final ListTag listTag3 = compoundTag.getList("entities", 10);
        final ObjectArrayList<StructureTemplate.StructureEntityInfo> entityInfosList = new ObjectArrayList<>(listTag3.size());
        for(int i = 0; i < listTag3.size(); ++i) {
            final CompoundTag compoundTag2 = listTag3.getCompound(i);
            final ListTag listTag4 = compoundTag2.getList("pos", 6);
            final Vec3 vec3 = new Vec3(listTag4.getDouble(0), listTag4.getDouble(1), listTag4.getDouble(2));
            final ListTag listTag5 = compoundTag2.getList(StructureTemplate.ENTITY_TAG_BLOCKPOS, 3);
            final BlockPos blockPos = new BlockPos(listTag5.getInt(0), listTag5.getInt(1), listTag5.getInt(2));
            if (compoundTag2.contains("nbt")) {
                CompoundTag compoundTag3 = compoundTag2.getCompound("nbt");
                entityInfosList.add(new StructureTemplate.StructureEntityInfo(vec3, blockPos, compoundTag3));
            }
        }

        synchronized (this.generator_accelerator$templateMutationLock) {
            this.size = loadedSize;
            this.palettes = generator_accelerator$publishList(loadedPalettes);
            this.entityInfoList = generator_accelerator$publishList(entityInfosList);
        }
    }

    /**
     * @author Sixik
     * @reason Save from stable list references without CopyOnWriteArrayList allocation.
     */
    @Overwrite
    public CompoundTag save(CompoundTag compoundTag) {
        final List<StructureTemplate.Palette> palettesSnapshot;
        final List<StructureTemplate.StructureEntityInfo> entityInfosSnapshot;
        final Vec3i sizeSnapshot;
        synchronized (this.generator_accelerator$templateMutationLock) {
            palettesSnapshot = this.palettes;
            entityInfosSnapshot = this.entityInfoList;
            sizeSnapshot = this.size;
        }

        if (palettesSnapshot.isEmpty()) {
            compoundTag.put("blocks", new ListTag());
            compoundTag.put("palette", new ListTag());
        } else {
            ObjectArrayList<StructureTemplate.SimplePalette> list = new ObjectArrayList<>(palettesSnapshot.size());
            StructureTemplate.SimplePalette simplePalette = new StructureTemplate.SimplePalette();
            list.add(simplePalette);

            for (int i = 1; i < palettesSnapshot.size(); ++i) {
                list.add(new StructureTemplate.SimplePalette());
            }

            ListTag listTag = new ListTag();

            List<StructureTemplate.StructureBlockInfo> list2 = palettesSnapshot.get(0).blocks();
            for (int j = 0; j < list2.size(); ++j) {
                StructureTemplate.StructureBlockInfo structureBlockInfo = list2.get(j);
                CompoundTag compoundTag2 = new CompoundTag();
                compoundTag2.put("pos", generator_accelerator$newIntegerList(structureBlockInfo.pos().getX(), structureBlockInfo.pos().getY(), structureBlockInfo.pos().getZ()));

                int k = simplePalette.idFor(structureBlockInfo.state());
                compoundTag2.putInt("state", k);
                if (structureBlockInfo.nbt() != null) {
                    compoundTag2.put("nbt", structureBlockInfo.nbt());
                }
                ConfluenceStructureTemplateCompat.save(structureBlockInfo, compoundTag2);
                listTag.add(compoundTag2);

                for (int l = 1; l < palettesSnapshot.size(); ++l) {
                    StructureTemplate.SimplePalette simplePalette2 = list.get(l);
                    simplePalette2.addMapping(palettesSnapshot.get(l).blocks().get(j).state(), k);
                }
            }
            compoundTag.put("blocks", listTag);

            ListTag listTag2 = new ListTag();
            if (list.size() == 1) {
                for (BlockState blockState : simplePalette) {
                    listTag2.add(NbtUtils.writeBlockState(blockState));
                }
                compoundTag.put("palette", listTag2);
            } else {
                for (StructureTemplate.SimplePalette simplePalette3 : list) {
                    ListTag listTag3 = new ListTag();
                    for (BlockState blockState2 : simplePalette3) {
                        listTag3.add(NbtUtils.writeBlockState(blockState2));
                    }
                    listTag2.add(listTag3);
                }
                compoundTag.put("palettes", listTag2);
            }
        }

        ListTag listTag4 = new ListTag();

        for (int i = 0; i < entityInfosSnapshot.size(); i++) {
            StructureTemplate.StructureEntityInfo structureEntityInfo = entityInfosSnapshot.get(i);
            CompoundTag compoundTag3 = new CompoundTag();
            compoundTag3.put("pos", generator_accelerator$newDoubleList(structureEntityInfo.pos.x, structureEntityInfo.pos.y, structureEntityInfo.pos.z));
            compoundTag3.put("blockPos", generator_accelerator$newIntegerList(structureEntityInfo.blockPos.getX(), structureEntityInfo.blockPos.getY(), structureEntityInfo.blockPos.getZ()));
            if (structureEntityInfo.nbt != null) {
                compoundTag3.put("nbt", structureEntityInfo.nbt);
            }
            listTag4.add(compoundTag3);
        }

        compoundTag.put("entities", listTag4);
        compoundTag.put("size", generator_accelerator$newIntegerList(sizeSnapshot.getX(), sizeSnapshot.getY(), sizeSnapshot.getZ()));
        return NbtUtils.addCurrentDataVersion(compoundTag);
    }

    @Unique
    private static StructureTemplate.Palette generator_accelerator$loadPalette(HolderGetter<Block> holderGetter, ListTag paletteTag, ListTag blocksTag) {
        StructureTemplate.SimplePalette simplePalette = new StructureTemplate.SimplePalette();
        for (int i = 0; i < paletteTag.size(); ++i) {
            simplePalette.addMapping(NbtUtils.readBlockState(holderGetter, paletteTag.getCompound(i)), i);
        }

        ObjectArrayList<StructureTemplate.StructureBlockInfo> solidBlocks = new ObjectArrayList<>();
        ObjectArrayList<StructureTemplate.StructureBlockInfo> nbtBlocks = new ObjectArrayList<>();
        ObjectArrayList<StructureTemplate.StructureBlockInfo> otherBlocks = new ObjectArrayList<>();
        for (int j = 0; j < blocksTag.size(); ++j) {
            CompoundTag blockInfoTag = blocksTag.getCompound(j);
            ListTag posTag = blockInfoTag.getList("pos", 3);
            BlockPos blockPos = new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2));
            BlockState blockState = simplePalette.stateFor(blockInfoTag.getInt(StructureTemplate.BLOCK_TAG_STATE));
            CompoundTag blockNbt = blockInfoTag.contains("nbt")
                    ? StructureBlockEntityNbtSanitizer.sanitizeTemplateNbt(blockState, blockInfoTag.getCompound("nbt"))
                    : null;
            StructureTemplate.StructureBlockInfo structureBlockInfo = new StructureTemplate.StructureBlockInfo(blockPos, blockState, blockNbt);
            ConfluenceStructureTemplateCompat.load(blockInfoTag, structureBlockInfo);
            addToLists(structureBlockInfo, solidBlocks, nbtBlocks, otherBlocks);
        }

        return StructureTemplatePaletteInvoker.generator_accelerator$createPalette(
                generator_accelerator$buildInfoList(solidBlocks, nbtBlocks, otherBlocks)
        );
    }

    @Unique
    private static ObjectArrayList<StructureTemplate.StructureBlockInfo> generator_accelerator$buildInfoList(
            List<StructureTemplate.StructureBlockInfo> solidBlocks,
            List<StructureTemplate.StructureBlockInfo> nbtBlocks,
            List<StructureTemplate.StructureBlockInfo> otherBlocks
    ) {
        Comparator<StructureTemplate.StructureBlockInfo> comparator = Comparator
                .comparingInt((StructureTemplate.StructureBlockInfo info) -> info.pos().getY())
                .thenComparingInt(info -> info.pos().getX())
                .thenComparingInt(info -> info.pos().getZ());
        solidBlocks.sort(comparator);
        otherBlocks.sort(comparator);
        nbtBlocks.sort(comparator);

        ObjectArrayList<StructureTemplate.StructureBlockInfo> result = new ObjectArrayList<>(
                solidBlocks.size() + otherBlocks.size() + nbtBlocks.size()
        );
        result.addAll(solidBlocks);
        result.addAll(otherBlocks);
        result.addAll(nbtBlocks);
        result.trim();
        return result;
    }

    @Unique
    private static List<StructureTemplate.StructureEntityInfo> generator_accelerator$collectEntityList(Level level, BlockPos min, BlockPos max) {
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, AABB.encapsulatingFullBlocks(min, max), entity -> !(entity instanceof Player));
        ObjectArrayList<StructureTemplate.StructureEntityInfo> result = new ObjectArrayList<>(entities.size());
        for (Entity entity : entities) {
            Vec3 vec3 = new Vec3(entity.getX() - (double) min.getX(), entity.getY() - (double) min.getY(), entity.getZ() - (double) min.getZ());
            CompoundTag compoundTag = new CompoundTag();
            entity.save(compoundTag);
            BlockPos blockPos = entity instanceof Painting painting ? painting.getPos().subtract(min) : BlockPos.containing(vec3);
            result.add(new StructureTemplate.StructureEntityInfo(vec3, blockPos, compoundTag.copy()));
        }
        return generator_accelerator$publishList(result);
    }

    @Unique
    private static <T> List<T> generator_accelerator$publishList(ObjectArrayList<T> list) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        list.trim();
        return Collections.unmodifiableList(list);
    }

    @Unique
    private static ListTag generator_accelerator$newIntegerList(int... values) {
        ListTag listTag = new ListTag();
        for (int i = 0; i < values.length; i++) {
            listTag.add(IntTag.valueOf(values[i]));
        }
        return listTag;
    }

    @Unique
    private static ListTag generator_accelerator$newDoubleList(double... values) {
        ListTag listTag = new ListTag();
        for (int i = 0; i < values.length; i++) {
            listTag.add(DoubleTag.valueOf(values[i]));
        }
        return listTag;
    }
}
