package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.Objects;

/**
 * Detached facade passed to selected unknown worldgen units instead of a live world.
 */
public final class GATransactionSandboxContext {
    private final String unitId;
    private final GATransactionSandboxWriter writer;

    public GATransactionSandboxContext(String unitId, GATransactionSandboxWriter writer) {
        this.unitId = unitId == null || unitId.isBlank() ? "unknown" : unitId.trim();
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public String unitId() {
        return unitId;
    }

    public GATransactionSandboxWriter writer() {
        return writer;
    }

    public boolean setBlock(int x, int y, int z, Object state, int flags) {
        return writer.setBlock(x, y, z, state, flags);
    }

    public boolean markPostprocess(int x, int y, int z) {
        return writer.markPostprocess(x, y, z);
    }

    public boolean scheduleFluidTick(int x, int y, int z, Object fluid, int delay, int priority) {
        return writer.scheduleFluidTick(x, y, z, fluid, delay, priority);
    }

    public boolean scheduleBlockTick(int x, int y, int z, Object block, int delay, int priority) {
        return writer.scheduleBlockTick(x, y, z, block, delay, priority);
    }

    public boolean unsupportedRead(String reason) {
        return writer.unsupportedRead(reason);
    }

    public boolean unsupportedWrite(String reason) {
        return writer.unsupportedWrite(reason);
    }
}
