package dev.sixik.generator_accelerator.common.fluid;

import net.minecraft.world.level.block.state.BlockState;

public final class GAFluidSpreadCache {
    public final StateCache states = new StateCache(64);
    public final BooleanCache holes = new BooleanCache(64);

    public void clear() {
        this.states.clear();
        this.holes.clear();
    }

    public static final class StateCache {
        private short[] keys;
        private BlockState[] values;
        private int size;

        private StateCache(int capacity) {
            this.keys = new short[capacity];
            this.values = new BlockState[capacity];
        }

        public void clear() {
            this.size = 0;
        }

        public BlockState get(short key) {
            short[] keys = this.keys;
            for (int i = 0, size = this.size; i < size; i++) {
                if (keys[i] == key) {
                    return this.values[i];
                }
            }
            return null;
        }

        public void put(short key, BlockState value) {
            int size = this.size;
            if (size == this.keys.length) {
                this.grow();
            }
            this.keys[size] = key;
            this.values[size] = value;
            this.size = size + 1;
        }

        private void grow() {
            int newLength = this.keys.length << 1;
            short[] newKeys = new short[newLength];
            BlockState[] newValues = new BlockState[newLength];
            System.arraycopy(this.keys, 0, newKeys, 0, this.keys.length);
            System.arraycopy(this.values, 0, newValues, 0, this.values.length);
            this.keys = newKeys;
            this.values = newValues;
        }
    }

    public static final class BooleanCache {
        private short[] keys;
        private boolean[] values;
        private int size;

        private BooleanCache(int capacity) {
            this.keys = new short[capacity];
            this.values = new boolean[capacity];
        }

        public void clear() {
            this.size = 0;
        }

        public int indexOf(short key) {
            short[] keys = this.keys;
            for (int i = 0, size = this.size; i < size; i++) {
                if (keys[i] == key) {
                    return i;
                }
            }
            return -1;
        }

        public boolean valueAt(int index) {
            return this.values[index];
        }

        public void put(short key, boolean value) {
            int size = this.size;
            if (size == this.keys.length) {
                this.grow();
            }
            this.keys[size] = key;
            this.values[size] = value;
            this.size = size + 1;
        }

        private void grow() {
            int newLength = this.keys.length << 1;
            short[] newKeys = new short[newLength];
            boolean[] newValues = new boolean[newLength];
            System.arraycopy(this.keys, 0, newKeys, 0, this.keys.length);
            System.arraycopy(this.values, 0, newValues, 0, this.values.length);
            this.keys = newKeys;
            this.values = newValues;
        }
    }
}
