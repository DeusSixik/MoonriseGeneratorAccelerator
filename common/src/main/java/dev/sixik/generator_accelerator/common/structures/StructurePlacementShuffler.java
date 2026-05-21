package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Allocation-light shuffle views used only inside JigsawPlacement hot loops.
 * They preserve vanilla Fisher-Yates order and RNG consumption.
 */
public final class StructurePlacementShuffler {
    public static final int DEFERRED_TEMPLATE_SHUFFLE_MAX_SIZE = 32;

    private static final Rotation[] ROTATIONS = {
            Rotation.NONE,
            Rotation.CLOCKWISE_90,
            Rotation.CLOCKWISE_180,
            Rotation.COUNTERCLOCKWISE_90
    };

    private static final ThreadLocal<ReusableShuffledList<Rotation>> ROTATION_SCRATCH =
            ThreadLocal.withInitial(ReusableShuffledList::new);
    private static final ThreadLocal<TemplateShuffleScratch<StructurePoolElement>> TEMPLATE_SCRATCH =
            ThreadLocal.withInitial(TemplateShuffleScratch::new);

    private StructurePlacementShuffler() {
    }

    public static ListView<Rotation> shuffledRotations(RandomSource random) {
        return ROTATION_SCRATCH.get().reset(ROTATIONS, random);
    }

    public static ListView<StructurePoolElement> shuffledTemplates(StructurePoolElement[] templates, RandomSource random) {
        return TEMPLATE_SCRATCH.get().next().reset(templates, random);
    }

    public static boolean shouldUseDeferredTemplateShuffle(int size) {
        return size <= DEFERRED_TEMPLATE_SHUFFLE_MAX_SIZE;
    }

    public static void materializeTemplates(List<StructurePoolElement> templates) {
        if (templates instanceof DeferredShuffledList<?> deferred) {
            deferred.materialize();
        }
    }

    public abstract static class ListView<T> extends AbstractList<T> {
    }

    private static final class DeferredShuffledList<T> extends ListView<T> {
        private T[] source;
        private RandomSource random;
        private Object[] shuffled;

        DeferredShuffledList<T> reset(T[] source, RandomSource random) {
            this.source = source;
            this.random = random;
            this.shuffled = null;
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException(index);
            }
            return (T) materialize()[index];
        }

        @Override
        public int size() {
            return this.source.length;
        }

        @Override
        public Object[] toArray() {
            return materialize();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E> E[] toArray(E[] target) {
            Object[] result = materialize();
            int size = result.length;
            if (target.length < size) {
                return (E[]) Arrays.copyOf(result, size, target.getClass());
            }
            System.arraycopy(result, 0, target, 0, size);
            if (target.length > size) {
                target[size] = null;
            }
            return target;
        }

        private Object[] materialize() {
            Object[] result = this.shuffled;
            if (result != null) {
                return result;
            }

            int size = this.source.length;
            result = new Object[size];
            System.arraycopy(this.source, 0, result, 0, size);
            shuffle(result, size, this.random);
            this.random = null;
            this.shuffled = result;
            return result;
        }
    }

    private static final class TemplateShuffleScratch<T> {
        private final DeferredShuffledList<T> first = new DeferredShuffledList<>();
        private final DeferredShuffledList<T> second = new DeferredShuffledList<>();
        private int cursor;

        private DeferredShuffledList<T> next() {
            return (this.cursor++ & 1) == 0 ? this.first : this.second;
        }
    }

    private static final class ReusableShuffledList<T> extends ListView<T> implements Iterator<T> {
        private Object[] elements = new Object[8];
        private int size;
        private int cursor;

        ReusableShuffledList<T> reset(T[] source, RandomSource random) {
            int count = source.length;
            ensureCapacity(count);
            System.arraycopy(source, 0, this.elements, 0, count);
            if (count < this.size) {
                Arrays.fill(this.elements, count, this.size, null);
            }
            this.size = count;
            this.cursor = 0;
            shuffle(this.elements, count, random);
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get(int index) {
            if (index < 0 || index >= this.size) {
                throw new IndexOutOfBoundsException(index);
            }
            return (T) this.elements[index];
        }

        @Override
        public int size() {
            return this.size;
        }

        @Override
        public Iterator<T> iterator() {
            this.cursor = 0;
            return this;
        }

        @Override
        public boolean hasNext() {
            return this.cursor < this.size;
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return get(this.cursor++);
        }

        @Override
        public Object[] toArray() {
            return Arrays.copyOf(this.elements, this.size);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E> E[] toArray(E[] target) {
            if (target.length < this.size) {
                return (E[]) Arrays.copyOf(this.elements, this.size, target.getClass());
            }
            System.arraycopy(this.elements, 0, target, 0, this.size);
            if (target.length > this.size) {
                target[this.size] = null;
            }
            return target;
        }

        private void ensureCapacity(int requiredSize) {
            if (this.elements.length < requiredSize) {
                this.elements = new Object[Math.max(requiredSize, this.elements.length << 1)];
            }
        }
    }

    private static void shuffle(Object[] values, int size, RandomSource random) {
        for (int remaining = size; remaining > 1; remaining--) {
            int picked = random.nextInt(remaining);
            int last = remaining - 1;
            Object previousLast = values[last];
            values[last] = values[picked];
            values[picked] = previousLast;
        }
    }
}
