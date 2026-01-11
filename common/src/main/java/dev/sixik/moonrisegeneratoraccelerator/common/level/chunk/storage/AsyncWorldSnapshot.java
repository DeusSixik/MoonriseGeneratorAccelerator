package dev.sixik.moonrisegeneratoraccelerator.common.level.chunk.storage;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

public class AsyncWorldSnapshot {

    protected static @Nullable Registry<Biome> biomeRegistry = null;

    protected long LastUpdate;
    protected LevelLightEngine LevelLightEngine;
    protected RegistryAccess registryAccess;
    protected  StructurePieceSerializationContext structureSerializerContext;


    public AsyncWorldSnapshot(ServerLevel level) {
        if(biomeRegistry == null)
            biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        LastUpdate = level.getGameTime();
        LevelLightEngine = level.getChunkSource().getLightEngine();
        registryAccess = level.registryAccess();
        structureSerializerContext = StructurePieceSerializationContext.fromLevel(level);
    }
}
