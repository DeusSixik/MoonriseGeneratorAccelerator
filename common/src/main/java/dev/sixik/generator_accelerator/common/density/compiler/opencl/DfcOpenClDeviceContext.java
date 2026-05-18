package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

final class DfcOpenClDeviceContext implements AutoCloseable {
    private final DfcOpenClDeviceEnumerator.Candidate candidate;
    private final String buildLog;
    private final long context;
    private final long queue;
    private final long program;
    private final long slabVmKernel;
    private boolean closed;

    private DfcOpenClDeviceContext(DfcOpenClDeviceEnumerator.Candidate candidate, String buildLog,
                                   long context, long queue, long program, long slabVmKernel) {
        this.candidate = candidate;
        this.buildLog = buildLog;
        this.context = context;
        this.queue = queue;
        this.program = program;
        this.slabVmKernel = slabVmKernel;
    }

    static DfcOpenClDeviceContext create(DfcOpenClDeviceEnumerator.Candidate candidate) {
        ByteBuffer sourceBuffer = null;
        long context = 0L;
        long queue = 0L;
        long program = 0L;
        long slabVmKernel = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            PointerBuffer properties = stack.callocPointer(3);
            properties.put(CL12.CL_CONTEXT_PLATFORM).put(candidate.platform()).rewind();
            context = CL12.clCreateContext(properties, candidate.device(), null, 0L, err);
            check(err.get(0), "clCreateContext");

            queue = CL12.clCreateCommandQueue(context, candidate.device(), 0L, err);
            check(err.get(0), "clCreateCommandQueue");

            sourceBuffer = MemoryUtil.memUTF8(DfcOpenClSources.runtimeSource(), true);
            PointerBuffer sources = stack.callocPointer(1);
            sources.put(0, sourceBuffer);
            program = CL12.clCreateProgramWithSource(context, sources, null, err);
            check(err.get(0), "clCreateProgramWithSource");

            int buildError = CL12.clBuildProgram(program, candidate.device(), "-cl-std=CL1.2", null, 0L);
            String buildLog = getBuildLog(program, candidate.device());
            int buildStatus = getBuildStatus(program, candidate.device());
            if (buildStatus != CL12.CL_BUILD_SUCCESS) {
                throw new IllegalStateException("OpenCL runtime build failed: " + trimBuildLog(buildLog)
                        + " (error " + buildError + ")");
            }
            check(buildError, "clBuildProgram");

            slabVmKernel = CL12.clCreateKernel(program, "dfc_slab_vm_eval", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_eval)");

            return new DfcOpenClDeviceContext(candidate, trimBuildLog(buildLog), context, queue, program, slabVmKernel);
        } catch (Throwable throwable) {
            releaseKernel(slabVmKernel);
            releaseProgram(program);
            releaseQueue(queue);
            releaseContext(context);
            throw throwable;
        } finally {
            if (sourceBuffer != null) {
                MemoryUtil.memFree(sourceBuffer);
            }
        }
    }

    synchronized SlabVmResult evalSlabVm(SlabVmRequest request) {
        assertOpen();
        validateRequest(request);

        ByteBuffer bytecodeHost = null;
        DoubleBuffer constantsHost = null;
        DoubleBuffer slotsHost = null;
        DoubleBuffer outHost = null;
        long bytecodeBuffer = 0L;
        long constantsBuffer = 0L;
        long slotsBuffer = 0L;
        long outBuffer = 0L;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);

            bytecodeHost = MemoryUtil.memAlloc(request.bytecode.length);
            bytecodeHost.put(request.bytecode).flip();
            constantsHost = doubleHostBuffer(request.constants);
            slotsHost = doubleHostBuffer(request.slotRowsFlat);
            outHost = MemoryUtil.memAllocDouble(request.n);

            bytecodeBuffer = CL12.clCreateBuffer(this.context, CL12.CL_MEM_READ_ONLY | CL12.CL_MEM_COPY_HOST_PTR,
                    bytecodeHost, err);
            check(err.get(0), "clCreateBuffer(slab bytecode)");
            constantsBuffer = CL12.clCreateBuffer(this.context, CL12.CL_MEM_READ_ONLY | CL12.CL_MEM_COPY_HOST_PTR,
                    constantsHost, err);
            check(err.get(0), "clCreateBuffer(slab constants)");
            slotsBuffer = CL12.clCreateBuffer(this.context, CL12.CL_MEM_READ_ONLY | CL12.CL_MEM_COPY_HOST_PTR,
                    slotsHost, err);
            check(err.get(0), "clCreateBuffer(slab slots)");
            outBuffer = CL12.clCreateBuffer(this.context, CL12.CL_MEM_WRITE_ONLY,
                    (long) request.n * Double.BYTES, err);
            check(err.get(0), "clCreateBuffer(slab output)");

            int arg = 0;
            check(CL12.clSetKernelArg1p(this.slabVmKernel, arg++, bytecodeBuffer), "clSetKernelArg(slab bytecode)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.bytecode.length),
                    "clSetKernelArg(slab bytecode length)");
            check(CL12.clSetKernelArg1p(this.slabVmKernel, arg++, constantsBuffer),
                    "clSetKernelArg(slab constants)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.constants.length),
                    "clSetKernelArg(slab constant count)");
            check(CL12.clSetKernelArg1p(this.slabVmKernel, arg++, slotsBuffer), "clSetKernelArg(slab slots)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.slotCount),
                    "clSetKernelArg(slab slot count)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.slotRowStride),
                    "clSetKernelArg(slab row stride)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.cellStartX),
                    "clSetKernelArg(slab start x)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.cellStartZ),
                    "clSetKernelArg(slab start z)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.blockY), "clSetKernelArg(slab block y)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.cellWidth),
                    "clSetKernelArg(slab cell width)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.slabLayout),
                    "clSetKernelArg(slab layout)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.columnXi),
                    "clSetKernelArg(slab col xi)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.columnZi),
                    "clSetKernelArg(slab col zi)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg++, request.columnCellHeight),
                    "clSetKernelArg(slab cell height)");
            check(CL12.clSetKernelArg1d(this.slabVmKernel, arg++, request.hoistValue),
                    "clSetKernelArg(slab hoist)");
            check(CL12.clSetKernelArg1p(this.slabVmKernel, arg++, outBuffer), "clSetKernelArg(slab output)");
            check(CL12.clSetKernelArg1i(this.slabVmKernel, arg, request.n), "clSetKernelArg(slab n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmKernel, 1, null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(dfc_slab_vm_eval)");
            check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                    "clEnqueueReadBuffer(slab output)");
            check(CL12.clFinish(this.queue), "clFinish(slab eval)");
            outHost.get(request.out, 0, request.n);
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            releaseMem(outBuffer);
            releaseMem(slotsBuffer);
            releaseMem(constantsBuffer);
            releaseMem(bytecodeBuffer);
            free(outHost);
            free(slotsHost);
            free(constantsHost);
            free(bytecodeHost);
        }
    }

    DfcOpenClDeviceInfo deviceInfo() {
        return this.candidate.info();
    }

    String buildLog() {
        return this.buildLog;
    }

    boolean isFor(DfcOpenClDeviceEnumerator.Candidate candidate) {
        return this.candidate.platform() == candidate.platform() && this.candidate.device() == candidate.device();
    }

    synchronized boolean isOpen() {
        return !this.closed;
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        releaseKernel(this.slabVmKernel);
        releaseProgram(this.program);
        releaseQueue(this.queue);
        releaseContext(this.context);
    }

    private void assertOpen() {
        if (this.closed) {
            throw new IllegalStateException("OpenCL device context is closed");
        }
    }

    private static void validateRequest(SlabVmRequest request) {
        if (request.bytecode == null || request.bytecode.length == 0) {
            throw new IllegalArgumentException("bytecode is empty");
        }
        if (request.constants == null) {
            throw new IllegalArgumentException("constants is null");
        }
        if (request.slotRowsFlat == null) {
            throw new IllegalArgumentException("slotRowsFlat is null");
        }
        if (request.out == null) {
            throw new IllegalArgumentException("out is null");
        }
        if (request.n <= 0 || request.out.length < request.n) {
            throw new IllegalArgumentException("invalid output length");
        }
        if (request.cellWidth <= 0) {
            throw new IllegalArgumentException("cellWidth must be positive");
        }
        if (request.slotCount < 0 || request.slotRowStride < 0) {
            throw new IllegalArgumentException("invalid slot layout");
        }
        long neededSlots = (long) request.slotCount * request.slotRowStride;
        if (neededSlots > request.slotRowsFlat.length) {
            throw new IllegalArgumentException("slotRowsFlat is shorter than slotCount * slotRowStride");
        }
    }

    private static DoubleBuffer doubleHostBuffer(double[] values) {
        DoubleBuffer buffer = MemoryUtil.memAllocDouble(Math.max(1, values.length));
        if (values.length == 0) {
            buffer.put(0.0D);
        } else {
            buffer.put(values);
        }
        buffer.flip();
        return buffer;
    }

    private static int getBuildStatus(long program, long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer status = stack.callocInt(1);
            check(CL12.clGetProgramBuildInfo(program, device, CL12.CL_PROGRAM_BUILD_STATUS, status, null),
                    "clGetProgramBuildInfo(status)");
            return status.get(0);
        }
    }

    private static String getBuildLog(long program, long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.callocPointer(1);
            check(CL12.clGetProgramBuildInfo(program, device, CL12.CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, size),
                    "clGetProgramBuildInfo(log size)");
            int bytes = Math.toIntExact(size.get(0));
            if (bytes <= 1) {
                return "";
            }
            ByteBuffer log = MemoryUtil.memAlloc(bytes);
            try {
                check(CL12.clGetProgramBuildInfo(program, device, CL12.CL_PROGRAM_BUILD_LOG, log, null),
                        "clGetProgramBuildInfo(log)");
                int length = bytes;
                while (length > 0 && log.get(length - 1) == 0) {
                    length--;
                }
                return MemoryUtil.memUTF8(log, length);
            } finally {
                MemoryUtil.memFree(log);
            }
        }
    }

    private static String trimBuildLog(String buildLog) {
        if (buildLog == null) {
            return "";
        }
        String trimmed = buildLog.trim();
        if (trimmed.length() <= 2048) {
            return trimmed;
        }
        return trimmed.substring(0, 2048) + "...";
    }

    private static void check(int error, String op) {
        if (error != CL12.CL_SUCCESS) {
            throw new IllegalStateException(op + " failed with OpenCL error " + error);
        }
    }

    private static void releaseMem(long mem) {
        if (mem != 0L) {
            CL12.clReleaseMemObject(mem);
        }
    }

    private static void releaseKernel(long kernel) {
        if (kernel != 0L) {
            CL12.clReleaseKernel(kernel);
        }
    }

    private static void releaseProgram(long program) {
        if (program != 0L) {
            CL12.clReleaseProgram(program);
        }
    }

    private static void releaseQueue(long queue) {
        if (queue != 0L) {
            CL12.clReleaseCommandQueue(queue);
        }
    }

    private static void releaseContext(long context) {
        if (context != 0L) {
            CL12.clReleaseContext(context);
        }
    }

    private static void free(ByteBuffer buffer) {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }

    private static void free(DoubleBuffer buffer) {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }

    record SlabVmRequest(
            byte[] bytecode,
            double[] constants,
            double[] slotRowsFlat,
            int slotCount,
            int slotRowStride,
            int cellStartX,
            int cellStartZ,
            int blockY,
            int cellWidth,
            int slabLayout,
            int columnXi,
            int columnZi,
            int columnCellHeight,
            double hoistValue,
            double[] out,
            int n) {
    }

    record SlabVmResult(long elapsedNanos) {
    }
}
