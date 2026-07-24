package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.denismasterherobrine.packager.opencl.core.OpenClBuffer;
import dev.denismasterherobrine.packager.opencl.core.OpenClCommandQueue;
import dev.denismasterherobrine.packager.opencl.core.OpenClContext;
import dev.denismasterherobrine.packager.opencl.core.OpenClDevice;
import dev.denismasterherobrine.packager.opencl.core.OpenClDevices;
import dev.denismasterherobrine.packager.opencl.core.OpenClEvents;
import dev.denismasterherobrine.packager.opencl.core.OpenClKernel;
import dev.denismasterherobrine.packager.opencl.core.OpenClProgram;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

/**
 * Thin OpenCL launcher for the fixed GPU payload arithmetic kernel.
 *
 * <p>This is diagnostic-only until it proves faster than the JavaToGpu runtime
 * pipeline. It compiles the generated OpenCL source once, keeps the context and
 * buffers alive, and avoids JavaToGpu per-invocation descriptor/production stages.</p>
 */
public final class GpuPayloadDirectOpenClExecutor {
    private static final String DIRECT_LAUNCHER_CLASS = "dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.generated.GpuPayloadArithmeticKernel_computeBatch_GpuLauncher";
    private static final Object LOCK = new Object();

    private static Session session;

    private GpuPayloadDirectOpenClExecutor() {
    }

    public static GpuPayloadBatchExecutor.GpuAttempt tryComputeGpu(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output) {
        validate(payload, blockX, blockY, blockZ, externValues, output);
        if (output.length == 0) {
            return GpuPayloadBatchExecutor.GpuAttempt.ok();
        }
        synchronized (LOCK) {
            try {
                ensureSession().compute(payload, blockX, blockY, blockZ, externValues, output);
                return GpuPayloadBatchExecutor.GpuAttempt.ok();
            } catch (Throwable throwable) {
                closeSession();
                return GpuPayloadBatchExecutor.GpuAttempt.failed(throwable.toString());
            }
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            closeSession();
        }
    }

    private static Session ensureSession() throws ReflectiveOperationException {
        Session current = session;
        if (current != null) {
            return current;
        }
        OpenClDevices.ensureCreated();
        OpenClDevice device = OpenClDevices.selectBest();
        if (device == null) {
            throw new IllegalStateException("no OpenCL device available");
        }
        OpenClContext context = OpenClContext.create(device);
        OpenClCommandQueue queue = context.createQueue(false);
        OpenClProgram program = context.buildProgram(generatedString("KERNEL_SOURCE"));
        OpenClKernel kernel = program.createKernel(generatedString("KERNEL_NAME"));
        current = new Session(context, queue, program, kernel);
        session = current;
        return current;
    }

    private static String generatedString(String fieldName) throws ReflectiveOperationException {
        Class<?> launcherClass = Class.forName(DIRECT_LAUNCHER_CLASS);
        Field field = launcherClass.getField(fieldName);
        return (String) field.get(null);
    }

    private static void closeSession() {
        Session current = session;
        session = null;
        if (current != null) {
            current.close();
        }
    }

    private static void validate(
            GpuIrPayload payload,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] output) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(blockX, "blockX");
        Objects.requireNonNull(blockY, "blockY");
        Objects.requireNonNull(blockZ, "blockZ");
        Objects.requireNonNull(externValues, "externValues");
        Objects.requireNonNull(output, "output");
        if (blockX.length < output.length || blockY.length < output.length || blockZ.length < output.length) {
            throw new IllegalArgumentException("coordinate arrays must be at least as long as output");
        }
        int requiredExternValues = Math.multiplyExact(output.length, payload.externInputCount());
        if (externValues.length < requiredExternValues) {
            throw new IllegalArgumentException("extern input values length " + externValues.length
                    + " is smaller than required length " + requiredExternValues);
        }
    }

    private static final class Session implements AutoCloseable {
        private final OpenClContext context;
        private final OpenClCommandQueue queue;
        private final OpenClProgram program;
        private final OpenClKernel kernel;
        private final Buffers buffers = new Buffers();

        private Session(OpenClContext context, OpenClCommandQueue queue, OpenClProgram program, OpenClKernel kernel) {
            this.context = context;
            this.queue = queue;
            this.program = program;
            this.kernel = kernel;
        }

        private void compute(
                GpuIrPayload payload,
                int[] blockX,
                int[] blockY,
                int[] blockZ,
                double[] externValues,
                double[] output) {
            int points = output.length;
            int nodes = payload.nodeCount();
            buffers.ensure(context, points, nodes, payload.externInputCount());

            writeInt(buffers.blockX, blockX, points);
            writeInt(buffers.blockY, blockY, points);
            writeInt(buffers.blockZ, blockZ, points);
            writeInt(buffers.opcodes, payload.opcodes(), nodes);
            writeInt(buffers.arg0, payload.arg0(), nodes);
            writeInt(buffers.arg1, payload.arg1(), nodes);
            writeInt(buffers.arg2, payload.arg2(), nodes);
            writeInt(buffers.int0, payload.int0(), nodes);
            writeInt(buffers.int1, payload.int1(), nodes);
            writeDouble(buffers.value0, payload.value0(), nodes);
            writeDouble(buffers.value1, payload.value1(), nodes);
            if (payload.externInputCount() > 0) {
                writeDouble(buffers.externValues, externValues, points * payload.externInputCount());
            }

            kernel.setArg(0, buffers.blockX.buffer);
            kernel.setArg(1, buffers.blockY.buffer);
            kernel.setArg(2, buffers.blockZ.buffer);
            kernel.setArg(3, buffers.opcodes.buffer);
            kernel.setArg(4, buffers.arg0.buffer);
            kernel.setArg(5, buffers.arg1.buffer);
            kernel.setArg(6, buffers.arg2.buffer);
            kernel.setArg(7, buffers.int0.buffer);
            kernel.setArg(8, buffers.int1.buffer);
            kernel.setArg(9, buffers.value0.buffer);
            kernel.setArg(10, buffers.value1.buffer);
            kernel.setArgInt(11, payload.externInputCount());
            kernel.setArg(12, buffers.externValues.buffer);
            kernel.setArgInt(13, payload.rootIndex());
            kernel.setArgInt(14, nodes);
            kernel.setArg(15, buffers.scratch.buffer);
            kernel.setArg(16, buffers.output.buffer);

            long event = kernel.enqueue1D(queue, points, null);
            OpenClEvents.waitFor(event);
            OpenClEvents.release(event);
            readDouble(buffers.output, output, points);
        }

        private void writeInt(BufferSlot slot, int[] values, int length) {
            ByteBuffer bytes = slot.hostBytes(Integer.BYTES, length);
            IntBuffer ints = bytes.asIntBuffer();
            ints.put(values, 0, length);
            bytes.limit(Math.multiplyExact(length, Integer.BYTES));
            queue.writeBuffer(slot.buffer, true, 0L, bytes, null, null);
        }

        private void writeDouble(BufferSlot slot, double[] values, int length) {
            ByteBuffer bytes = slot.hostBytes(Double.BYTES, length);
            DoubleBuffer doubles = bytes.asDoubleBuffer();
            doubles.put(values, 0, length);
            bytes.limit(Math.multiplyExact(length, Double.BYTES));
            queue.writeBuffer(slot.buffer, true, 0L, bytes, null, null);
        }

        private void readDouble(BufferSlot slot, double[] values, int length) {
            ByteBuffer bytes = slot.hostBytes(Double.BYTES, length);
            bytes.limit(Math.multiplyExact(length, Double.BYTES));
            queue.readBuffer(slot.buffer, true, 0L, bytes, null, null);
            bytes.position(0);
            bytes.asDoubleBuffer().get(values, 0, length);
        }

        @Override
        public void close() {
            buffers.close();
            kernel.close();
            program.close();
            queue.close();
            context.close();
        }
    }

    private static final class Buffers implements AutoCloseable {
        private final BufferSlot blockX = new BufferSlot();
        private final BufferSlot blockY = new BufferSlot();
        private final BufferSlot blockZ = new BufferSlot();
        private final BufferSlot opcodes = new BufferSlot();
        private final BufferSlot arg0 = new BufferSlot();
        private final BufferSlot arg1 = new BufferSlot();
        private final BufferSlot arg2 = new BufferSlot();
        private final BufferSlot int0 = new BufferSlot();
        private final BufferSlot int1 = new BufferSlot();
        private final BufferSlot value0 = new BufferSlot();
        private final BufferSlot value1 = new BufferSlot();
        private final BufferSlot externValues = new BufferSlot();
        private final BufferSlot scratch = new BufferSlot();
        private final BufferSlot output = new BufferSlot();

        private void ensure(OpenClContext context, int points, int nodes, int externInputCount) {
            long pointInts = Math.max(1L, Math.multiplyExact((long) points, Integer.BYTES));
            long nodeInts = Math.max(1L, Math.multiplyExact((long) nodes, Integer.BYTES));
            long nodeDoubles = Math.max(1L, Math.multiplyExact((long) nodes, Double.BYTES));
            long externDoubles = Math.max(1L, Math.multiplyExact(Math.multiplyExact((long) points, externInputCount), Double.BYTES));
            long scratchDoubles = Math.max(1L, Math.multiplyExact(Math.multiplyExact((long) points, nodes), Double.BYTES));
            long outputDoubles = Math.max(1L, Math.multiplyExact((long) points, Double.BYTES));

            blockX.ensure(context, pointInts);
            blockY.ensure(context, pointInts);
            blockZ.ensure(context, pointInts);
            opcodes.ensure(context, nodeInts);
            arg0.ensure(context, nodeInts);
            arg1.ensure(context, nodeInts);
            arg2.ensure(context, nodeInts);
            int0.ensure(context, nodeInts);
            int1.ensure(context, nodeInts);
            value0.ensure(context, nodeDoubles);
            value1.ensure(context, nodeDoubles);
            externValues.ensure(context, externDoubles);
            scratch.ensure(context, scratchDoubles);
            output.ensure(context, outputDoubles);
        }

        @Override
        public void close() {
            blockX.close();
            blockY.close();
            blockZ.close();
            opcodes.close();
            arg0.close();
            arg1.close();
            arg2.close();
            int0.close();
            int1.close();
            value0.close();
            value1.close();
            externValues.close();
            scratch.close();
            output.close();
        }
    }

    private static final class BufferSlot implements AutoCloseable {
        private OpenClBuffer buffer;
        private long capacityBytes;
        private ByteBuffer host;

        private void ensure(OpenClContext context, long requiredBytes) {
            if (buffer != null && capacityBytes >= requiredBytes) {
                return;
            }
            close();
            capacityBytes = Math.max(1L, requiredBytes);
            buffer = context.createReadWriteBuffer(capacityBytes);
        }

        private ByteBuffer hostBytes(int elementBytes, int elements) {
            int requiredBytes = Math.multiplyExact(elements, elementBytes);
            if (host == null || host.capacity() < requiredBytes) {
                host = ByteBuffer.allocateDirect(Math.max(1, requiredBytes)).order(ByteOrder.nativeOrder());
            }
            host.clear();
            host.limit(requiredBytes);
            return host;
        }

        @Override
        public void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            capacityBytes = 0L;
        }
    }
}
