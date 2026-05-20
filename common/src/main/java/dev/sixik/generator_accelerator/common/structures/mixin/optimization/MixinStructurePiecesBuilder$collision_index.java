package dev.sixik.generator_accelerator.common.structures.mixin.optimization;

import dev.sixik.generator_accelerator.common.structures.StructurePieceCollisionIndex;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@Mixin(StructurePiecesBuilder.class)
public abstract class MixinStructurePiecesBuilder$collision_index {

    @Unique
    private static final int generator_accelerator$collisionIndexThreshold = 12;

    @Unique
    @Nullable
    private StructurePieceCollisionIndex generator_accelerator$collisionIndex;

    @Shadow
    @Final
    private List<StructurePiece> pieces;

    /**
     * @author Sixik
     * @reason Maintain an x/z spatial index so large piece sets do not pay full linear collision scans.
     */
    @Overwrite
    public void addPiece(StructurePiece piece) {
        this.pieces.add(piece);
        StructurePieceCollisionIndex collisionIndex = this.generator_accelerator$collisionIndex;
        if (collisionIndex != null) {
            collisionIndex.add(piece);
        }
    }

    /**
     * @author Sixik
     * @reason Large modded/jigsaw structures often do O(n^2) bounding-box collision scans; use chunk buckets once the piece count is non-trivial.
     */
    @Overwrite
    public StructurePiece findCollisionPiece(BoundingBox box) {
        if (this.pieces.size() <= generator_accelerator$collisionIndexThreshold) {
            return StructurePiece.findCollisionPiece(this.pieces, box);
        }
        return this.generator_accelerator$getCollisionIndex().findCollision(box);
    }

    /**
     * @author Sixik
     * @reason Keep the collision index in sync with builder lifetime.
     */
    @Overwrite
    public void clear() {
        this.pieces.clear();
        this.generator_accelerator$collisionIndex = null;
    }

    @Unique
    private StructurePieceCollisionIndex generator_accelerator$getCollisionIndex() {
        StructurePieceCollisionIndex collisionIndex = this.generator_accelerator$collisionIndex;
        if (collisionIndex != null) {
            return collisionIndex;
        }

        collisionIndex = new StructurePieceCollisionIndex();
        for (int i = 0, size = this.pieces.size(); i < size; i++) {
            collisionIndex.add(this.pieces.get(i));
        }
        this.generator_accelerator$collisionIndex = collisionIndex;
        return collisionIndex;
    }
}
