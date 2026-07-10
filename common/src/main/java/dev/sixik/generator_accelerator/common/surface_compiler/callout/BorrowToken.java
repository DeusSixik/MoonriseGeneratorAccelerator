package dev.sixik.generator_accelerator.common.surface_compiler.callout;

public final class BorrowToken implements AutoCloseable {
    private boolean closed;

    public void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("surface borrow token already closed");
        }
    }

    public boolean closed() {
        return this.closed;
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
