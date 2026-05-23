package dev.sixik.generator_accelerator.common.structures.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$ChunkGeneratorStructureStateExtern;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkGeneratorStructureState.class)
public abstract class MixinChunkGeneratorStructureState implements GA$ChunkGeneratorStructureStateExtern {

    @Shadow
    @Final
    private BiomeSource biomeSource;
    @Shadow
    @Final
    private Map<Structure, List<StructurePlacement>> placementsForStructure;
    @Shadow
    @Final
    private Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> ringPositions;

    @Shadow
    protected abstract CompletableFuture<List<ChunkPos>> generateRingPositions(Holder<StructureSet> arg, ConcentricRingsStructurePlacement arg2);

    @Unique
    private Holder<StructureSet>[] bts$possibleStructureSets;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(RandomState randomState, BiomeSource biomeSource, long l, long m, List<Holder<StructureSet>> pPossibleStructureSets, CallbackInfo ci) {
        this.bts$possibleStructureSets = (Holder<StructureSet>[]) pPossibleStructureSets.toArray(Holder[]::new);
    }

    @Override
    public Holder<StructureSet>[] getPossibleStructureSetsArray() {
        return bts$possibleStructureSets;
    }

    /**
     * @author Sixik
     * @reason Redirect to primitive array
     */
    @Overwrite
    private void generatePositions() {
        Set<Holder<Biome>> set = this.biomeSource.possibleBiomes();

        final Holder<StructureSet>[] array = this.getPossibleStructureSetsArray();
        for (int i = 0; i < array.length; i++) {
            final var element = array[i];
            StructureSet structureset = element.value();
            boolean flag = false;

            for (StructureSet.StructureSelectionEntry structureset$structureselectionentry : structureset.structures()) {
                Structure structure = structureset$structureselectionentry.structure().value();
                if (structure.biomes().stream().anyMatch(set::contains)) {
                    this.placementsForStructure.computeIfAbsent(structure, p_256235_ -> new ArrayList<>()).add(structureset.placement());
                    flag = true;
                }
            }

            if (flag && structureset.placement() instanceof ConcentricRingsStructurePlacement concentricringsstructureplacement) {
                this.ringPositions.put(concentricringsstructureplacement, this.generateRingPositions(element, concentricringsstructureplacement));
            }
        }
    }
}
