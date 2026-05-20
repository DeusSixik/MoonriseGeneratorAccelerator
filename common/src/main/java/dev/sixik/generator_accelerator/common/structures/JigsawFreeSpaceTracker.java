package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;

public final class JigsawFreeSpaceTracker {
    private static final double COLLISION_MARGIN = 0.25D;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.structure.jigsawFreeSpaceTracker", "true")
    );

    private final double rootMinX;
    private final double rootMinY;
    private final double rootMinZ;
    private final double rootMaxX;
    private final double rootMaxY;
    private final double rootMaxZ;
    private final Long2ObjectMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
    private final ObjectArrayList<BoundingBox> occupied = new ObjectArrayList<>();
    private final IntArrayList visitedMarks = new IntArrayList();
    private int queryStamp = 1;

    public JigsawFreeSpaceTracker(BoundingBox rootBounds) {
        this.rootMinX = rootBounds.minX();
        this.rootMinY = rootBounds.minY();
        this.rootMinZ = rootBounds.minZ();
        this.rootMaxX = rootBounds.maxX() + 1.0D;
        this.rootMaxY = rootBounds.maxY() + 1.0D;
        this.rootMaxZ = rootBounds.maxZ() + 1.0D;
    }

    public JigsawFreeSpaceTracker(BoundingBox rootBounds, BoundingBox initialOccupied) {
        this(rootBounds);
        this.occupy(initialOccupied);
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public boolean canPlace(BoundingBox candidate) {
        double minX = candidate.minX() + COLLISION_MARGIN;
        double minY = candidate.minY() + COLLISION_MARGIN;
        double minZ = candidate.minZ() + COLLISION_MARGIN;
        double maxX = candidate.maxX() + 1.0D - COLLISION_MARGIN;
        double maxY = candidate.maxY() + 1.0D - COLLISION_MARGIN;
        double maxZ = candidate.maxZ() + 1.0D - COLLISION_MARGIN;
        if (minX < this.rootMinX || minY < this.rootMinY || minZ < this.rootMinZ
                || maxX > this.rootMaxX || maxY > this.rootMaxY || maxZ > this.rootMaxZ) {
            return false;
        }

        int stamp = this.ga$nextStamp();
        int minChunkX = candidate.minX() >> 4;
        int maxChunkX = candidate.maxX() >> 4;
        int minChunkZ = candidate.minZ() >> 4;
        int maxChunkZ = candidate.maxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                IntArrayList bucket = this.buckets.get(ChunkPos.asLong(chunkX, chunkZ));
                if (bucket == null) {
                    continue;
                }

                for (int i = 0, size = bucket.size(); i < size; i++) {
                    int occupiedIndex = bucket.getInt(i);
                    if (this.visitedMarks.getInt(occupiedIndex) == stamp) {
                        continue;
                    }
                    this.visitedMarks.set(occupiedIndex, stamp);
                    BoundingBox box = this.occupied.get(occupiedIndex);
                    if (intersects(minX, minY, minZ, maxX, maxY, maxZ, box)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public void occupy(BoundingBox box) {
        int occupiedIndex = this.occupied.size();
        this.occupied.add(box);
        this.visitedMarks.add(0);

        int minChunkX = box.minX() >> 4;
        int maxChunkX = box.maxX() >> 4;
        int minChunkZ = box.minZ() >> 4;
        int maxChunkZ = box.maxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                this.buckets.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> new IntArrayList()).add(occupiedIndex);
            }
        }
    }

    public int occupiedCount() {
        return this.occupied.size();
    }

    public BoundingBox occupiedAt(int index) {
        return this.occupied.get(index);
    }

    public static boolean canPlace(VoxelShape freeShape, BoundingBox candidate) {
        return !Shapes.joinIsNotEmpty(
                freeShape,
                Shapes.create(AABB.of(candidate).deflate(COLLISION_MARGIN)),
                BooleanOp.ONLY_SECOND
        );
    }

    public static VoxelShape occupy(VoxelShape freeShape, BoundingBox box) {
        return Shapes.joinUnoptimized(freeShape, Shapes.create(AABB.of(box)), BooleanOp.ONLY_FIRST);
    }

    public static State ensureOuterState(MutableObject<VoxelShape> freeShape, BoundingBox initialOccupied) {
        if (freeShape instanceof State state) {
            return state;
        }

        VoxelShape shape = freeShape.getValue();
        if (shape == null) {
            throw new IllegalStateException("Expected outer jigsaw free shape to be initialized");
        }
        if (shape.isEmpty()) {
            return new State(shape, null);
        }
        return new State(shape, new JigsawFreeSpaceTracker(toBoundingBox(shape.bounds()), initialOccupied));
    }

    public static MutableObject<VoxelShape> createInnerFreeShape(BoundingBox rootBounds) {
        return ENABLED ? createInnerState(rootBounds) : new MutableObject<>(Shapes.create(AABB.of(rootBounds)));
    }

    public static State createInnerState(BoundingBox rootBounds) {
        return new State(Shapes.create(AABB.of(rootBounds)), new JigsawFreeSpaceTracker(rootBounds));
    }

    private static BoundingBox toBoundingBox(AABB box) {
        return new BoundingBox(
                Mth.floor(box.minX),
                Mth.floor(box.minY),
                Mth.floor(box.minZ),
                Mth.ceil(box.maxX) - 1,
                Mth.ceil(box.maxY) - 1,
                Mth.ceil(box.maxZ) - 1
        );
    }

    private static boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, BoundingBox box) {
        return maxX > box.minX()
                && minX < box.maxX() + 1.0D
                && maxY > box.minY()
                && minY < box.maxY() + 1.0D
                && maxZ > box.minZ()
                && minZ < box.maxZ() + 1.0D;
    }

    private int ga$nextStamp() {
        int next = this.queryStamp + 1;
        if (next == Integer.MAX_VALUE) {
            for (int i = 0, size = this.visitedMarks.size(); i < size; i++) {
                this.visitedMarks.set(i, 0);
            }
            next = 1;
        }
        this.queryStamp = next;
        return next;
    }

    public static final class State extends MutableObject<VoxelShape> {
        private JigsawFreeSpaceTracker tracker;
        private int materializedOccupied;

        private State(VoxelShape initialShape, JigsawFreeSpaceTracker tracker) {
            super(initialShape);
            this.tracker = tracker;
            this.materializedOccupied = tracker == null ? 0 : tracker.occupiedCount();
        }

        public boolean canPlace(BoundingBox candidate) {
            JigsawFreeSpaceTracker currentTracker = this.tracker;
            if (currentTracker != null) {
                return currentTracker.canPlace(candidate);
            }
            return JigsawFreeSpaceTracker.canPlace(super.getValue(), candidate);
        }

        public void occupy(BoundingBox box) {
            JigsawFreeSpaceTracker currentTracker = this.tracker;
            if (currentTracker != null) {
                currentTracker.occupy(box);
                return;
            }
            super.setValue(JigsawFreeSpaceTracker.occupy(super.getValue(), box));
        }

        @Override
        public VoxelShape getValue() {
            JigsawFreeSpaceTracker currentTracker = this.tracker;
            if (currentTracker != null) {
                int occupiedCount = currentTracker.occupiedCount();
                if (this.materializedOccupied < occupiedCount) {
                    VoxelShape shape = super.getValue();
                    for (int i = this.materializedOccupied; i < occupiedCount; i++) {
                        shape = JigsawFreeSpaceTracker.occupy(shape, currentTracker.occupiedAt(i));
                    }
                    this.materializedOccupied = occupiedCount;
                    super.setValue(shape);
                }
            }
            return super.getValue();
        }

        @Override
        public void setValue(VoxelShape value) {
            super.setValue(value);
            this.tracker = null;
            this.materializedOccupied = 0;
        }
    }
}
