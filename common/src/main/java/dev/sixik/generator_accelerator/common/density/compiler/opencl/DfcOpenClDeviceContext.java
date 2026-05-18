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
    private final long slabVmCoordsKernel;
    private final long slabVmCellGridKernel;
    private final DeviceBuffer slabBytecodeBuffer = new DeviceBuffer();
    private final DeviceBuffer slabConstantsBuffer = new DeviceBuffer();
    private final DeviceBuffer slabSlotsBuffer = new DeviceBuffer();
    private final DeviceBuffer slabOutBuffer = new DeviceBuffer();
    private final DeviceBuffer coordsBytecodeBuffer = new DeviceBuffer();
    private final DeviceBuffer coordsConstantsBuffer = new DeviceBuffer();
    private final DeviceBuffer coordsSlotsBuffer = new DeviceBuffer();
    private final DeviceBuffer coordsBlockXBuffer = new DeviceBuffer();
    private final DeviceBuffer coordsBlockYBuffer = new DeviceBuffer();
    private final DeviceBuffer coordsBlockZBuffer = new DeviceBuffer();
    private final DeviceBuffer coordsHoistBuffer = new DeviceBuffer();
    private final DeviceBuffer coordsOutBuffer = new DeviceBuffer();
    private final DeviceBuffer gridBytecodeBuffer = new DeviceBuffer();
    private final DeviceBuffer gridConstantsBuffer = new DeviceBuffer();
    private final DeviceBuffer gridSlotsBuffer = new DeviceBuffer();
    private final DeviceBuffer gridOutBuffer = new DeviceBuffer();
    private boolean closed;

    private DfcOpenClDeviceContext(DfcOpenClDeviceEnumerator.Candidate candidate, String buildLog,
                                   long context, long queue, long program, long slabVmKernel,
                                   long slabVmCoordsKernel, long slabVmCellGridKernel) {
        this.candidate = candidate;
        this.buildLog = buildLog;
        this.context = context;
        this.queue = queue;
        this.program = program;
        this.slabVmKernel = slabVmKernel;
        this.slabVmCoordsKernel = slabVmCoordsKernel;
        this.slabVmCellGridKernel = slabVmCellGridKernel;
    }

    static DfcOpenClDeviceContext create(DfcOpenClDeviceEnumerator.Candidate candidate) {
        ByteBuffer sourceBuffer = null;
        long context = 0L;
        long queue = 0L;
        long program = 0L;
        long slabVmKernel = 0L;
        long slabVmCoordsKernel = 0L;
        long slabVmCellGridKernel = 0L;
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
            slabVmCoordsKernel = CL12.clCreateKernel(program, "dfc_slab_vm_eval_coords", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_eval_coords)");
            slabVmCellGridKernel = CL12.clCreateKernel(program, "dfc_slab_vm_eval_cell_grid", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_eval_cell_grid)");

            return new DfcOpenClDeviceContext(candidate, trimBuildLog(buildLog), context, queue, program, slabVmKernel,
                    slabVmCoordsKernel, slabVmCellGridKernel);
        } catch (Throwable throwable) {
            releaseKernel(slabVmCellGridKernel);
            releaseKernel(slabVmCoordsKernel);
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
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);

            bytecodeHost = MemoryUtil.memAlloc(request.bytecode.length);
            bytecodeHost.put(request.bytecode).flip();
            outHost = MemoryUtil.memAllocDouble(request.n);

            long bytecodeBuffer = ensureBuffer(this.slabBytecodeBuffer, request.bytecode.length,
                    CL12.CL_MEM_READ_ONLY, err, "slab bytecode");
            long constantsBuffer = ensureBuffer(this.slabConstantsBuffer,
                    doubleBytes(request.constants.length), CL12.CL_MEM_READ_ONLY, err, "slab constants");
            long slotsBuffer = ensureBuffer(this.slabSlotsBuffer,
                    doubleBytes(request.slotRowsFlat.length), CL12.CL_MEM_READ_ONLY, err, "slab slots");
            long outBuffer = ensureBuffer(this.slabOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "slab output");

            check(CL12.clEnqueueWriteBuffer(this.queue, bytecodeBuffer, true, 0L, bytecodeHost, null, null),
                    "clEnqueueWriteBuffer(slab bytecode)");
            writeDoubleArray(constantsBuffer, request.constants, "clEnqueueWriteBuffer(slab constants)");
            writeDoubleArray(slotsBuffer, request.slotRowsFlat, "clEnqueueWriteBuffer(slab slots)");

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
            free(outHost);
            free(bytecodeHost);
        }
    }

    synchronized SlabVmResult evalSlabVmCoords(SlabVmCoordsRequest request) {
        assertOpen();
        validateCoordsRequest(request);

        ByteBuffer bytecodeHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);

            bytecodeHost = MemoryUtil.memAlloc(request.bytecode.length);
            bytecodeHost.put(request.bytecode).flip();
            outHost = MemoryUtil.memAllocDouble(request.n);

            long bytecodeBuffer = ensureBuffer(this.coordsBytecodeBuffer, request.bytecode.length,
                    CL12.CL_MEM_READ_ONLY, err, "coord bytecode");
            long constantsBuffer = ensureBuffer(this.coordsConstantsBuffer,
                    doubleBytes(request.constants.length), CL12.CL_MEM_READ_ONLY, err, "coord constants");
            long slotsBuffer = ensureBuffer(this.coordsSlotsBuffer,
                    doubleBytes(request.slotRowsFlat.length), CL12.CL_MEM_READ_ONLY, err, "coord slots");
            long blockXBuffer = ensureBuffer(this.coordsBlockXBuffer,
                    doubleBytes(request.blockX.length), CL12.CL_MEM_READ_ONLY, err, "coord x");
            long blockYBuffer = ensureBuffer(this.coordsBlockYBuffer,
                    doubleBytes(request.blockY.length), CL12.CL_MEM_READ_ONLY, err, "coord y");
            long blockZBuffer = ensureBuffer(this.coordsBlockZBuffer,
                    doubleBytes(request.blockZ.length), CL12.CL_MEM_READ_ONLY, err, "coord z");
            long hoistBuffer = ensureBuffer(this.coordsHoistBuffer,
                    doubleBytes(request.hoist.length), CL12.CL_MEM_READ_ONLY, err, "coord hoist");
            long outBuffer = ensureBuffer(this.coordsOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "coord output");

            check(CL12.clEnqueueWriteBuffer(this.queue, bytecodeBuffer, true, 0L, bytecodeHost, null, null),
                    "clEnqueueWriteBuffer(coord bytecode)");
            writeDoubleArray(constantsBuffer, request.constants, "clEnqueueWriteBuffer(coord constants)");
            writeDoubleArray(slotsBuffer, request.slotRowsFlat, "clEnqueueWriteBuffer(coord slots)");
            writeDoubleArray(blockXBuffer, request.blockX, "clEnqueueWriteBuffer(coord x)");
            writeDoubleArray(blockYBuffer, request.blockY, "clEnqueueWriteBuffer(coord y)");
            writeDoubleArray(blockZBuffer, request.blockZ, "clEnqueueWriteBuffer(coord z)");
            writeDoubleArray(hoistBuffer, request.hoist, "clEnqueueWriteBuffer(coord hoist)");

            int arg = 0;
            check(CL12.clSetKernelArg1p(this.slabVmCoordsKernel, arg++, bytecodeBuffer),
                    "clSetKernelArg(coord bytecode)");
            check(CL12.clSetKernelArg1i(this.slabVmCoordsKernel, arg++, request.bytecode.length),
                    "clSetKernelArg(coord bytecode length)");
            check(CL12.clSetKernelArg1p(this.slabVmCoordsKernel, arg++, constantsBuffer),
                    "clSetKernelArg(coord constants)");
            check(CL12.clSetKernelArg1i(this.slabVmCoordsKernel, arg++, request.constants.length),
                    "clSetKernelArg(coord constant count)");
            check(CL12.clSetKernelArg1p(this.slabVmCoordsKernel, arg++, slotsBuffer),
                    "clSetKernelArg(coord slots)");
            check(CL12.clSetKernelArg1i(this.slabVmCoordsKernel, arg++, request.slotCount),
                    "clSetKernelArg(coord slot count)");
            check(CL12.clSetKernelArg1i(this.slabVmCoordsKernel, arg++, request.slotRowStride),
                    "clSetKernelArg(coord row stride)");
            check(CL12.clSetKernelArg1p(this.slabVmCoordsKernel, arg++, blockXBuffer),
                    "clSetKernelArg(coord x)");
            check(CL12.clSetKernelArg1p(this.slabVmCoordsKernel, arg++, blockYBuffer),
                    "clSetKernelArg(coord y)");
            check(CL12.clSetKernelArg1p(this.slabVmCoordsKernel, arg++, blockZBuffer),
                    "clSetKernelArg(coord z)");
            check(CL12.clSetKernelArg1p(this.slabVmCoordsKernel, arg++, hoistBuffer),
                    "clSetKernelArg(coord hoist)");
            check(CL12.clSetKernelArg1p(this.slabVmCoordsKernel, arg++, outBuffer),
                    "clSetKernelArg(coord output)");
            check(CL12.clSetKernelArg1i(this.slabVmCoordsKernel, arg, request.n), "clSetKernelArg(coord n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmCoordsKernel, 1,
                    null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(dfc_slab_vm_eval_coords)");
            check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                    "clEnqueueReadBuffer(coord output)");
            check(CL12.clFinish(this.queue), "clFinish(coord slab eval)");
            outHost.get(request.out, 0, request.n);
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(outHost);
            free(bytecodeHost);
        }
    }

    synchronized SlabVmResult evalSlabVmCellGrid(SlabVmCellGridRequest request) {
        assertOpen();
        validateCellGridRequest(request);

        ByteBuffer bytecodeHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);

            bytecodeHost = MemoryUtil.memAlloc(request.bytecode.length);
            bytecodeHost.put(request.bytecode).flip();
            outHost = MemoryUtil.memAllocDouble(request.n);

            long bytecodeBuffer = ensureBuffer(this.gridBytecodeBuffer, request.bytecode.length,
                    CL12.CL_MEM_READ_ONLY, err, "grid bytecode");
            long constantsBuffer = ensureBuffer(this.gridConstantsBuffer,
                    doubleBytes(request.constants.length), CL12.CL_MEM_READ_ONLY, err, "grid constants");
            long slotsBuffer = ensureBuffer(this.gridSlotsBuffer,
                    doubleBytes(request.slotRowsFlat.length), CL12.CL_MEM_READ_ONLY, err, "grid slots");
            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "grid output");

            check(CL12.clEnqueueWriteBuffer(this.queue, bytecodeBuffer, true, 0L, bytecodeHost, null, null),
                    "clEnqueueWriteBuffer(grid bytecode)");
            writeDoubleArray(constantsBuffer, request.constants, "clEnqueueWriteBuffer(grid constants)");
            writeDoubleArray(slotsBuffer, request.slotRowsFlat, "clEnqueueWriteBuffer(grid slots)");

            int arg = 0;
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, bytecodeBuffer),
                    "clSetKernelArg(grid bytecode)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.bytecode.length),
                    "clSetKernelArg(grid bytecode length)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, constantsBuffer),
                    "clSetKernelArg(grid constants)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.constants.length),
                    "clSetKernelArg(grid constant count)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, slotsBuffer),
                    "clSetKernelArg(grid slots)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.slotCount),
                    "clSetKernelArg(grid slot count)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.slotRowStride),
                    "clSetKernelArg(grid row stride)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockX),
                    "clSetKernelArg(grid first x)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockY),
                    "clSetKernelArg(grid first y)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockZ),
                    "clSetKernelArg(grid first z)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cellWidth),
                    "clSetKernelArg(grid cell width)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cellHeight),
                    "clSetKernelArg(grid cell height)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cells),
                    "clSetKernelArg(grid cells)");
            check(CL12.clSetKernelArg1d(this.slabVmCellGridKernel, arg++, request.hoistBase),
                    "clSetKernelArg(grid hoist)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, outBuffer),
                    "clSetKernelArg(grid output)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg, request.n),
                    "clSetKernelArg(grid n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmCellGridKernel, 1,
                    null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(dfc_slab_vm_eval_cell_grid)");
            check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                    "clEnqueueReadBuffer(grid output)");
            check(CL12.clFinish(this.queue), "clFinish(grid slab eval)");
            outHost.get(request.out, 0, request.n);
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(outHost);
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
        releaseDeviceBuffers();
        releaseKernel(this.slabVmCellGridKernel);
        releaseKernel(this.slabVmCoordsKernel);
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

    private static void validateCoordsRequest(SlabVmCoordsRequest request) {
        if (request.bytecode == null || request.bytecode.length == 0) {
            throw new IllegalArgumentException("bytecode is empty");
        }
        if (request.constants == null) {
            throw new IllegalArgumentException("constants is null");
        }
        if (request.slotRowsFlat == null) {
            throw new IllegalArgumentException("slotRowsFlat is null");
        }
        if (request.blockX == null || request.blockY == null || request.blockZ == null || request.hoist == null) {
            throw new IllegalArgumentException("coordinate arrays must be non-null");
        }
        if (request.out == null) {
            throw new IllegalArgumentException("out is null");
        }
        if (request.n <= 0 || request.out.length < request.n
                || request.blockX.length < request.n
                || request.blockY.length < request.n
                || request.blockZ.length < request.n
                || request.hoist.length < request.n) {
            throw new IllegalArgumentException("invalid coord batch length");
        }
        if (request.slotCount < 0 || request.slotRowStride < request.n) {
            throw new IllegalArgumentException("invalid slot layout");
        }
        long neededSlots = (long) request.slotCount * request.slotRowStride;
        if (neededSlots > request.slotRowsFlat.length) {
            throw new IllegalArgumentException("slotRowsFlat is shorter than slotCount * slotRowStride");
        }
    }

    private static void validateCellGridRequest(SlabVmCellGridRequest request) {
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
        if (request.cellWidth <= 0 || request.cellHeight <= 0 || request.cells <= 0) {
            throw new IllegalArgumentException("invalid cell grid dimensions");
        }
        long expectedN = (long) request.cellWidth * request.cellWidth * request.cellHeight * request.cells;
        if (expectedN > Integer.MAX_VALUE || request.n != (int) expectedN) {
            throw new IllegalArgumentException("cell grid element count does not match n");
        }
        if (request.out.length < request.n) {
            throw new IllegalArgumentException("invalid output length");
        }
        if (request.slotCount < 0 || request.slotRowStride < request.n) {
            throw new IllegalArgumentException("invalid slot layout");
        }
        long neededSlots = (long) request.slotCount * request.slotRowStride;
        if (neededSlots > request.slotRowsFlat.length) {
            throw new IllegalArgumentException("slotRowsFlat is shorter than slotCount * slotRowStride");
        }
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

    private long ensureBuffer(DeviceBuffer buffer, long bytes, long flags, IntBuffer err, String name) {
        long requestedBytes = Math.max(1L, bytes);
        if (buffer.mem != 0L && buffer.bytes >= requestedBytes && buffer.flags == flags) {
            return buffer.mem;
        }
        buffer.release();
        buffer.mem = CL12.clCreateBuffer(this.context, flags, requestedBytes, err);
        check(err.get(0), "clCreateBuffer(" + name + ")");
        buffer.bytes = requestedBytes;
        buffer.flags = flags;
        return buffer.mem;
    }

    private void writeDoubleArray(long buffer, double[] values, String op) {
        if (values.length == 0) {
            return;
        }
        check(CL12.clEnqueueWriteBuffer(this.queue, buffer, true, 0L, values, null, null), op);
    }

    private static long doubleBytes(int elements) {
        return (long) Math.max(1, elements) * Double.BYTES;
    }

    private void releaseDeviceBuffers() {
        this.gridOutBuffer.release();
        this.gridSlotsBuffer.release();
        this.gridConstantsBuffer.release();
        this.gridBytecodeBuffer.release();
        this.coordsOutBuffer.release();
        this.coordsHoistBuffer.release();
        this.coordsBlockZBuffer.release();
        this.coordsBlockYBuffer.release();
        this.coordsBlockXBuffer.release();
        this.coordsSlotsBuffer.release();
        this.coordsConstantsBuffer.release();
        this.coordsBytecodeBuffer.release();
        this.slabOutBuffer.release();
        this.slabSlotsBuffer.release();
        this.slabConstantsBuffer.release();
        this.slabBytecodeBuffer.release();
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

    record SlabVmCoordsRequest(
            byte[] bytecode,
            double[] constants,
            double[] slotRowsFlat,
            int slotCount,
            int slotRowStride,
            double[] blockX,
            double[] blockY,
            double[] blockZ,
            double[] hoist,
            double[] out,
            int n) {
    }

    record SlabVmCellGridRequest(
            byte[] bytecode,
            double[] constants,
            double[] slotRowsFlat,
            int slotCount,
            int slotRowStride,
            int firstBlockX,
            int firstBlockY,
            int firstBlockZ,
            int cellWidth,
            int cellHeight,
            int cells,
            double hoistBase,
            double[] out,
            int n) {
    }

    record SlabVmResult(long elapsedNanos) {
    }

    private static final class DeviceBuffer {
        long mem;
        long bytes;
        long flags;

        void release() {
            releaseMem(this.mem);
            this.mem = 0L;
            this.bytes = 0L;
            this.flags = 0L;
        }
    }
}
