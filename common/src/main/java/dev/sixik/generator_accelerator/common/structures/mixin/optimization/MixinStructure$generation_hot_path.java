package dev.sixik.generator_accelerator.common.structures.mixin.optimization;

import dev.sixik.generator_accelerator.common.structures.StructureGenerationHotPath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;
import java.util.function.Predicate;

@Mixin(Structure.class)
public abstract class MixinStructure$generation_hot_path {

    @Shadow
    protected abstract Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext generationContext);

    @Shadow
    public abstract StructureType<?> type();

    /**
     * @author Sixik
     * @reason Remove per-call WorldgenRandom allocation from the common structure start path.
     */
    @Overwrite
    public StructureStart generate(
            RegistryAccess registryAccess,
            ChunkGenerator chunkGenerator,
            BiomeSource biomeSource,
            RandomState randomState,
            StructureTemplateManager structureTemplateManager,
            long seed,
            ChunkPos chunkPos,
            int references,
            LevelHeightAccessor heightAccessor,
            Predicate<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> validBiome
    ) {
        Structure.GenerationContext generationContext = StructureGenerationHotPath.createContext(
                registryAccess,
                chunkGenerator,
                biomeSource,
                randomState,
                structureTemplateManager,
                seed,
                chunkPos,
                heightAccessor,
                validBiome
        );

        Optional<Structure.GenerationStub> generationStub = this.findValidGenerationPoint(generationContext);
        if (generationStub.isEmpty()) {
            return StructureStart.INVALID_START;
        }

        StructurePiecesBuilder piecesBuilder = generationStub.get().getPiecesBuilder();
        StructureStart structureStart = new StructureStart(
                (Structure) (Object) this,
                chunkPos,
                references,
                piecesBuilder.build()
        );
        return structureStart.isValid() ? structureStart : StructureStart.INVALID_START;
    }

    /**
     * @author Sixik
     * @reason Keep biome validation in a straight-line path instead of Optional.filter lambda allocation.
     */
    @Overwrite
    public Optional<Structure.GenerationStub> findValidGenerationPoint(Structure.GenerationContext generationContext) {
        Optional<Structure.GenerationStub> generationStub = this.findGenerationPoint(generationContext);
        if (generationStub.isEmpty()) {
            return Optional.empty();
        }

        Structure.GenerationStub stub = generationStub.get();
        return StructureGenerationHotPath.isValidBiome(stub, generationContext) ? generationStub : Optional.empty();
    }

    /**
     * @author Sixik
     * @reason Avoid the transient int[4] allocation used by vanilla corner height helpers.
     */
    @Overwrite
    public static int getMeanFirstOccupiedHeight(Structure.GenerationContext generationContext, int x, int z, int width, int depth) {
        return StructureGenerationHotPath.getMeanFirstOccupiedHeight(generationContext, x, z, width, depth);
    }

}
