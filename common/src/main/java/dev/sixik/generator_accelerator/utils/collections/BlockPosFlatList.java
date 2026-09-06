package dev.sixik.generator_accelerator.utils.collections;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class BlockPosFlatList implements Iterable<BlockPos> {

    private final LongArrayList packedPositions;
    private final ReusableBlockPosIterator iterator = new ReusableBlockPosIterator();

    public BlockPosFlatList() {
        this.packedPositions = new LongArrayList();
    }

    public BlockPosFlatList(int capacity) {
        this.packedPositions = new LongArrayList(capacity);
    }

    /**
     * Добавление без создания BlockPos вообще (самый быстрый способ)
     */
    public void add(int x, int y, int z) {
        this.packedPositions.add(BlockPos.asLong(x, y, z));
    }

    /**
     * Упаковывает переданный BlockPos в long
     */
    public void add(BlockPos pos) {
        this.packedPositions.add(pos.asLong());
    }

    public int size() {
        return this.packedPositions.size();
    }

    public boolean isEmpty() {
        return this.packedPositions.isEmpty();
    }

    public void clear() {
        this.packedPositions.clear();
    }

    /**
     * Доступ по индексу без аллокации через мутацию переданного объекта
     */
    public void getInto(int index, BlockPos.MutableBlockPos target) {
        target.set(this.packedPositions.getLong(index));
    }

    /**
     * Быстрый доступ к упакованному значению (для хэшмап, сетов или сохранения)
     */
    public long getPacked(int index) {
        return this.packedPositions.getLong(index);
    }

    /**
     * Обычный get() (создает объект, использовать только когда действительно нужен неизменяемый экземпляр)
     */
    public BlockPos get(int index) {
        return BlockPos.of(this.packedPositions.getLong(index));
    }

    @Override
    public Iterator<BlockPos> iterator() {
        this.iterator.cursor = 0;
        return this.iterator;
    }

    /**
     * Нулевая аллокация: итератор переиспользуется,
     * а возвращаемый BlockPos просто мутирует при каждом next()
     */
    private final class ReusableBlockPosIterator implements Iterator<BlockPos> {
        private int cursor = 0;
        private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        @Override
        public boolean hasNext() {
            return this.cursor < BlockPosFlatList.this.packedPositions.size();
        }

        @Override
        public BlockPos next() {
            int i = this.cursor;
            if (i >= BlockPosFlatList.this.packedPositions.size()) {
                throw new NoSuchElementException();
            }
            this.cursor = i + 1;

            // Мутируем существующий экземпляр
            return this.mutablePos.set(BlockPosFlatList.this.packedPositions.getLong(i));
        }
    }
}
