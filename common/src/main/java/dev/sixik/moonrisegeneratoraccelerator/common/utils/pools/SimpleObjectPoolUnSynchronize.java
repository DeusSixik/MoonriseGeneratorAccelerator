package dev.sixik.moonrisegeneratoraccelerator.common.utils.pools;

import java.util.function.Consumer;
import java.util.function.Function;

public class SimpleObjectPoolUnSynchronize<T> extends SimpleObjectPool<T>{

    public SimpleObjectPoolUnSynchronize(Function<SimpleObjectPool<T>, T> constructor, Consumer<T> initializer, Consumer<T> postRelease, int size) {
        super(constructor, initializer, postRelease, size);
    }

    public T alloc() {
        final T object;
        if (this.allocatedCount >= this.size) {
            object = this.constructor.apply(this);
            return object;
        }

        final int ordinal = this.allocatedCount++;
        object = (T) this.cachedObjects[ordinal];
        this.cachedObjects[ordinal] = null;

        this.initializer.accept(object);

        return object;
    }

    public void release(T object) {
        if (this.allocatedCount == 0) return;
        this.postRelease.accept(object);
        this.cachedObjects[--this.allocatedCount] = object;
    }
}
