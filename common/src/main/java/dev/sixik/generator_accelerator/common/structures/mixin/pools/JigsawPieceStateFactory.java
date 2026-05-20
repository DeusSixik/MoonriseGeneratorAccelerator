package dev.sixik.generator_accelerator.common.structures.mixin.pools;

import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;

final class JigsawPieceStateFactory {
    private static final MethodHandle CONSTRUCTOR = ga$findConstructor();

    private JigsawPieceStateFactory() {
    }

    static Object ga$create(PoolElementStructurePiece piece, MutableObject<VoxelShape> free, int depth) {
        try {
            return CONSTRUCTOR.invoke(piece, free, depth);
        } catch (RuntimeException | Error ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException("Failed to create JigsawPlacement PieceState", ex);
        }
    }

    private static MethodHandle ga$findConstructor() {
        for (Class<?> nestedClass : JigsawPlacement.class.getDeclaredClasses()) {
            try {
                Constructor<?> constructor = nestedClass.getDeclaredConstructor(PoolElementStructurePiece.class, MutableObject.class, int.class);
                constructor.setAccessible(true);
                return MethodHandles.lookup().unreflectConstructor(constructor);
            } catch (NoSuchMethodException ignored) {
                // Keep searching; PieceState is package-private and should be identified by its constructor shape.
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Unable to access JigsawPlacement PieceState constructor", ex);
            }
        }
        throw new IllegalStateException("Unable to find JigsawPlacement PieceState constructor");
    }
}
