package dev.sixik.generator_accelerator.common.features.vm;

public final class FeatureScratchStack {
    private FeatureScratch[] stack = new FeatureScratch[] {
            new FeatureScratch(),
            new FeatureScratch(),
            new FeatureScratch(),
            new FeatureScratch()
    };
    private int depth;

    FeatureScratch acquire() {
        int index = this.depth++;
        if (index >= this.stack.length) {
            grow(index + 1);
        }
        return this.stack[index];
    }

    void release(FeatureScratch scratch) {
        scratch.reset();
        this.depth--;
    }

    private void grow(int capacity) {
        int oldLength = this.stack.length;
        int nextLength = oldLength + (oldLength >> 1) + 1;
        if (nextLength < capacity) {
            nextLength = capacity;
        }

        FeatureScratch[] next = new FeatureScratch[nextLength];
        System.arraycopy(this.stack, 0, next, 0, oldLength);
        for (int i = oldLength; i < nextLength; i++) {
            next[i] = new FeatureScratch();
        }
        this.stack = next;
    }
}
