package dev.sixik.generator_accelerator.utils.collections;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;

import java.util.NoSuchElementException;

public class FlatObjectArrayList<ELEMENT> extends ObjectArrayList<ELEMENT> {

    private ReusableIterator reusableIterator;

    public FlatObjectArrayList() {
        super();
    }

    public FlatObjectArrayList(int capacity) {
        super(capacity);
    }

    @Override
    public ObjectListIterator<ELEMENT> iterator() {
        return listIterator(0);
    }

    @Override
    public ObjectListIterator<ELEMENT> listIterator() {
        return listIterator(0);
    }

    @Override
    public ObjectListIterator<ELEMENT> listIterator(int index) {
        if (index < 0 || index > this.size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + this.size);
        }

        if (this.reusableIterator == null) {
            this.reusableIterator = new ReusableIterator();
        }

        // Reset position before start
        this.reusableIterator.cursor = index;
        this.reusableIterator.lastRet = -1;
        return this.reusableIterator;
    }

    private final class ReusableIterator implements ObjectListIterator<ELEMENT> {
        private int cursor = 0;
        private int lastRet = -1;

        @Override
        public boolean hasNext() {
            return this.cursor < FlatObjectArrayList.this.size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ELEMENT next() {
            int i = this.cursor;
            if (i >= FlatObjectArrayList.this.size) {
                throw new NoSuchElementException();
            }
            this.cursor = i + 1;
            this.lastRet = i;
            return (ELEMENT) FlatObjectArrayList.this.a[i];
        }

        @Override
        public boolean hasPrevious() {
            return this.cursor > 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ELEMENT previous() {
            int i = this.cursor - 1;
            if (i < 0) {
                throw new NoSuchElementException();
            }
            this.cursor = i;
            this.lastRet = i;
            return (ELEMENT) FlatObjectArrayList.this.a[i];
        }

        @Override
        public int nextIndex() {
            return this.cursor;
        }

        @Override
        public int previousIndex() {
            return this.cursor - 1;
        }

        @Override
        public void remove() {
            if (this.lastRet < 0) {
                throw new IllegalStateException();
            }
            FlatObjectArrayList.this.remove(this.lastRet);
            if (this.lastRet < this.cursor) {
                this.cursor--;
            }
            this.lastRet = -1;
        }

        @Override
        public void set(ELEMENT e) {
            if (this.lastRet < 0) {
                throw new IllegalStateException();
            }
            FlatObjectArrayList.this.set(this.lastRet, e);
        }

        @Override
        public void add(ELEMENT e) {
            int i = this.cursor;
            FlatObjectArrayList.this.add(i, e);
            this.lastRet = -1;
            this.cursor = i + 1;
        }

        @Override
        public int skip(int n) {
            if (n < 0) {
                throw new IllegalArgumentException("Argument must be nonnegative: " + n);
            }
            int remaining = FlatObjectArrayList.this.size - this.cursor;
            if (n < remaining) {
                this.cursor += n;
                return n;
            }
            this.cursor = FlatObjectArrayList.this.size;
            return remaining;
        }

        @Override
        public int back(int n) {
            if (n < 0) {
                throw new IllegalArgumentException("Argument must be nonnegative: " + n);
            }
            if (n < this.cursor) {
                this.cursor -= n;
                return n;
            }
            int actual = this.cursor;
            this.cursor = 0;
            return actual;
        }
    }
}
