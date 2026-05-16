package dev.sixik.generator_accelerator.common.features.compat.galosphere;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class GACrystalSpikeConfigAccessors {
    private static final ClassValue<GACrystalSpikeConfigAccessors> ACCESSORS = new ClassValue<>() {
        @Override
        protected GACrystalSpikeConfigAccessors computeValue(Class<?> type) {
            return new GACrystalSpikeConfigAccessors(type);
        }
    };

    private static final MethodType OBJECT_TO_BLOCK_STATE = MethodType.methodType(BlockState.class, Object.class);
    private static final MethodType OBJECT_TO_INT_PROVIDER = MethodType.methodType(IntProvider.class, Object.class);
    private static final MethodType OBJECT_TO_CAVE_SURFACE = MethodType.methodType(CaveSurface.class, Object.class);
    private static final MethodType OBJECT_TO_FLOAT = MethodType.methodType(float.class, Object.class);

    private final MethodHandle crystalState;
    private final MethodHandle clusterState;
    private final MethodHandle glintedCluster;
    private final MethodHandle xzRadius;
    private final MethodHandle crystalDirection;
    private final MethodHandle glintedClusterChance;

    private GACrystalSpikeConfigAccessors(Class<?> type) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            this.crystalState = lookup.findVirtual(type, "crystal_state", MethodType.methodType(BlockState.class)).asType(OBJECT_TO_BLOCK_STATE);
            this.clusterState = lookup.findVirtual(type, "cluster_state", MethodType.methodType(BlockState.class)).asType(OBJECT_TO_BLOCK_STATE);
            this.glintedCluster = lookup.findVirtual(type, "glinted_cluster", MethodType.methodType(BlockState.class)).asType(OBJECT_TO_BLOCK_STATE);
            this.xzRadius = lookup.findVirtual(type, "xzRadius", MethodType.methodType(IntProvider.class)).asType(OBJECT_TO_INT_PROVIDER);
            this.crystalDirection = lookup.findVirtual(type, "crystal_direction", MethodType.methodType(CaveSurface.class)).asType(OBJECT_TO_CAVE_SURFACE);
            this.glintedClusterChance = lookup.findVirtual(type, "glinted_cluster_chance", MethodType.methodType(float.class)).asType(OBJECT_TO_FLOAT);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Unsupported Galosphere CrystalSpikeConfig shape: " + type.getName(), exception);
        }
    }

    public static GACrystalSpikeConfigAccessors of(Object config) {
        return ACCESSORS.get(config.getClass());
    }

    public BlockState crystalState(Object config) {
        try {
            return (BlockState) this.crystalState.invokeExact(config);
        } catch (Throwable throwable) {
            throw configAccessFailure(throwable);
        }
    }

    public BlockState clusterState(Object config) {
        try {
            return (BlockState) this.clusterState.invokeExact(config);
        } catch (Throwable throwable) {
            throw configAccessFailure(throwable);
        }
    }

    public BlockState glintedCluster(Object config) {
        try {
            return (BlockState) this.glintedCluster.invokeExact(config);
        } catch (Throwable throwable) {
            throw configAccessFailure(throwable);
        }
    }

    public IntProvider xzRadius(Object config) {
        try {
            return (IntProvider) this.xzRadius.invokeExact(config);
        } catch (Throwable throwable) {
            throw configAccessFailure(throwable);
        }
    }

    public CaveSurface crystalDirection(Object config) {
        try {
            return (CaveSurface) this.crystalDirection.invokeExact(config);
        } catch (Throwable throwable) {
            throw configAccessFailure(throwable);
        }
    }

    public float glintedClusterChance(Object config) {
        try {
            return (float) this.glintedClusterChance.invokeExact(config);
        } catch (Throwable throwable) {
            throw configAccessFailure(throwable);
        }
    }

    private static RuntimeException configAccessFailure(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Unable to access Galosphere CrystalSpikeConfig", throwable);
    }
}
