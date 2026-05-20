package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

final class DfcOpenClDeviceContext implements AutoCloseable {
    private static final double[] EMPTY_EXTERNAL_SLOTS = new double[]{0.0D};

    private final DfcOpenClDeviceEnumerator.Candidate candidate;
    private final String buildLog;
    private final long context;
    private final long queue;
    private final long program;
    private final long slabVmKernel;
    private final long slabVmCoordsKernel;
    private final long slabVmCellGridKernel;
    private final long slabVmCellGridSlotBufferKernel;
    private final long slabVmFillDemoSlotsKernel;
    private final long slabVmFillNoiseSlotsKernel;
    private final long slabVmFillNoiseSlotsBySlotKernel;
    private final long slabVmDirectDemoKernel;
    private final long slabVmDirectNoiseKernel;
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
    private final DeviceBuffer noisePermutationsBuffer = new DeviceBuffer();
    private final DeviceBuffer noiseOriginsBuffer = new DeviceBuffer();
    private final DeviceBuffer noiseInputFactorsBuffer = new DeviceBuffer();
    private final DeviceBuffer noiseAmpFactorsBuffer = new DeviceBuffer();
    private final DeviceBuffer noiseBranchOctaveOffsetsBuffer = new DeviceBuffer();
    private final DeviceBuffer noiseBranchOctaveCountsBuffer = new DeviceBuffer();
    private final DeviceBuffer noiseBranchScalesBuffer = new DeviceBuffer();
    private final DeviceBuffer noiseSlotFactorsBuffer = new DeviceBuffer();
    private final DeviceBuffer generatedExternalSlotsBuffer = new DeviceBuffer();
    private final DeviceBuffer generatedSlotBuffer = new DeviceBuffer();
    private final DeviceBuffer flatCache2dValuesBuffer = new DeviceBuffer();
    private final DeviceBuffer flatCache2dSlotCompactIndicesBuffer = new DeviceBuffer();
    private final DeviceBuffer flatCache2dSlotTableIndicesBuffer = new DeviceBuffer();
    private final DeviceBuffer flatCache2dTableOffsetsBuffer = new DeviceBuffer();
    private final DeviceBuffer flatCache2dTableSidesBuffer = new DeviceBuffer();
    private final DeviceBuffer flatCache2dTableFirstNoiseXBuffer = new DeviceBuffer();
    private final DeviceBuffer flatCache2dTableFirstNoiseZBuffer = new DeviceBuffer();
    private final Map<String, GeneratedNoiseKernel> generatedKernelCache = new HashMap<>();
    private final HostDoubleBuffer doubleStagingBuffer = new HostDoubleBuffer();
    private final HostDoubleBuffer gridOutHostBuffer = new HostDoubleBuffer();
    private boolean closed;

    private DfcOpenClDeviceContext(DfcOpenClDeviceEnumerator.Candidate candidate, String buildLog,
                                   long context, long queue, long program, long slabVmKernel,
                                   long slabVmCoordsKernel, long slabVmCellGridKernel,
                                   long slabVmCellGridSlotBufferKernel,
                                   long slabVmFillDemoSlotsKernel, long slabVmFillNoiseSlotsKernel,
                                   long slabVmFillNoiseSlotsBySlotKernel, long slabVmDirectDemoKernel,
                                   long slabVmDirectNoiseKernel) {
        this.candidate = candidate;
        this.buildLog = buildLog;
        this.context = context;
        this.queue = queue;
        this.program = program;
        this.slabVmKernel = slabVmKernel;
        this.slabVmCoordsKernel = slabVmCoordsKernel;
        this.slabVmCellGridKernel = slabVmCellGridKernel;
        this.slabVmCellGridSlotBufferKernel = slabVmCellGridSlotBufferKernel;
        this.slabVmFillDemoSlotsKernel = slabVmFillDemoSlotsKernel;
        this.slabVmFillNoiseSlotsKernel = slabVmFillNoiseSlotsKernel;
        this.slabVmFillNoiseSlotsBySlotKernel = slabVmFillNoiseSlotsBySlotKernel;
        this.slabVmDirectDemoKernel = slabVmDirectDemoKernel;
        this.slabVmDirectNoiseKernel = slabVmDirectNoiseKernel;
    }

    static DfcOpenClDeviceContext create(DfcOpenClDeviceEnumerator.Candidate candidate) {
        ByteBuffer sourceBuffer = null;
        long context = 0L;
        long queue = 0L;
        long program = 0L;
        long slabVmKernel = 0L;
        long slabVmCoordsKernel = 0L;
        long slabVmCellGridKernel = 0L;
        long slabVmCellGridSlotBufferKernel = 0L;
        long slabVmFillDemoSlotsKernel = 0L;
        long slabVmFillNoiseSlotsKernel = 0L;
        long slabVmFillNoiseSlotsBySlotKernel = 0L;
        long slabVmDirectDemoKernel = 0L;
        long slabVmDirectNoiseKernel = 0L;
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
            slabVmCellGridSlotBufferKernel = CL12.clCreateKernel(program,
                    "dfc_slab_vm_eval_cell_grid_slot_buffer", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_eval_cell_grid_slot_buffer)");
            slabVmFillDemoSlotsKernel = CL12.clCreateKernel(program, "dfc_slab_vm_fill_demo_slots", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_fill_demo_slots)");
            slabVmFillNoiseSlotsKernel = CL12.clCreateKernel(program, "dfc_slab_vm_fill_noise_slots", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_fill_noise_slots)");
            slabVmFillNoiseSlotsBySlotKernel = CL12.clCreateKernel(program,
                    "dfc_slab_vm_fill_noise_slots_by_slot", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_fill_noise_slots_by_slot)");
            slabVmDirectDemoKernel = CL12.clCreateKernel(program, "dfc_slab_vm_eval_cell_grid_direct_demo", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_eval_cell_grid_direct_demo)");
            slabVmDirectNoiseKernel = CL12.clCreateKernel(program, "dfc_slab_vm_eval_cell_grid_direct_noise", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_eval_cell_grid_direct_noise)");

            return new DfcOpenClDeviceContext(candidate, trimBuildLog(buildLog), context, queue, program, slabVmKernel,
                    slabVmCoordsKernel, slabVmCellGridKernel, slabVmCellGridSlotBufferKernel, slabVmFillDemoSlotsKernel,
                    slabVmFillNoiseSlotsKernel, slabVmFillNoiseSlotsBySlotKernel, slabVmDirectDemoKernel,
                    slabVmDirectNoiseKernel);
        } catch (Throwable throwable) {
            releaseKernel(slabVmDirectNoiseKernel);
            releaseKernel(slabVmDirectDemoKernel);
            releaseKernel(slabVmFillNoiseSlotsBySlotKernel);
            releaseKernel(slabVmFillNoiseSlotsKernel);
            releaseKernel(slabVmFillDemoSlotsKernel);
            releaseKernel(slabVmCellGridSlotBufferKernel);
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
        return evalSlabVmCellGrid(request, true, true);
    }

    synchronized SlabVmResult evalSlabVmCellGridNoRead(SlabVmCellGridRequest request) {
        return evalSlabVmCellGrid(request, false, true);
    }

    synchronized SlabVmResult evalSlabVmCellGridReuseInputs(SlabVmCellGridRequest request) {
        return evalSlabVmCellGrid(request, true, false);
    }

    synchronized SlabVmResult evalSlabVmCellGridNoReadReuseInputs(SlabVmCellGridRequest request) {
        return evalSlabVmCellGrid(request, false, false);
    }

    private SlabVmResult evalSlabVmCellGrid(SlabVmCellGridRequest request, boolean readOutput,
                                            boolean uploadInputs) {
        assertOpen();
        validateCellGridRequest(request);

        ByteBuffer bytecodeHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);

            boolean writeInputs = uploadInputs || !gridInputsReady(request);
            if (writeInputs) {
                bytecodeHost = MemoryUtil.memAlloc(request.bytecode.length);
                bytecodeHost.put(request.bytecode).flip();
            }
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long bytecodeBuffer = ensureBuffer(this.gridBytecodeBuffer, request.bytecode.length,
                    CL12.CL_MEM_READ_ONLY, err, "grid bytecode");
            long constantsBuffer = ensureBuffer(this.gridConstantsBuffer,
                    doubleBytes(request.constants.length), CL12.CL_MEM_READ_ONLY, err, "grid constants");
            long slotsBuffer = ensureBuffer(this.gridSlotsBuffer,
                    doubleBytes(request.slotRowsFlat.length), CL12.CL_MEM_READ_ONLY, err, "grid slots");
            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "grid output");

            if (writeInputs) {
                check(CL12.clEnqueueWriteBuffer(this.queue, bytecodeBuffer, true, 0L, bytecodeHost, null, null),
                        "clEnqueueWriteBuffer(grid bytecode)");
                writeDoubleArray(constantsBuffer, request.constants, "clEnqueueWriteBuffer(grid constants)");
                writeDoubleArray(slotsBuffer, request.slotRowsFlat, "clEnqueueWriteBuffer(grid slots)");
            }

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
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ),
                    "clSetKernelArg(grid layout)");
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
            if (readOutput) {
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(grid output)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
            }
            check(CL12.clFinish(this.queue), "clFinish(grid slab eval)");
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(bytecodeHost);
        }
    }

    synchronized SlabVmResult evalSlabVmCellGridGeneratedSlots(SlabVmGeneratedCellGridRequest request,
                                                               boolean readOutput) {
        assertOpen();
        validateGeneratedCellGridRequest(request);

        ByteBuffer bytecodeHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);

            bytecodeHost = MemoryUtil.memAlloc(request.bytecode.length);
            bytecodeHost.put(request.bytecode).flip();
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long bytecodeBuffer = ensureBuffer(this.gridBytecodeBuffer, request.bytecode.length,
                    CL12.CL_MEM_READ_ONLY, err, "generated grid bytecode");
            long constantsBuffer = ensureBuffer(this.gridConstantsBuffer,
                    doubleBytes(request.constants.length), CL12.CL_MEM_READ_ONLY, err, "generated grid constants");
            long slotsBuffer = ensureBuffer(this.gridSlotsBuffer,
                    doubleBytes(request.n * request.slotCount), CL12.CL_MEM_READ_WRITE, err, "generated grid slots");
            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "generated grid output");

            check(CL12.clEnqueueWriteBuffer(this.queue, bytecodeBuffer, true, 0L, bytecodeHost, null, null),
                    "clEnqueueWriteBuffer(generated grid bytecode)");
            writeDoubleArray(constantsBuffer, request.constants, "clEnqueueWriteBuffer(generated grid constants)");

            int fillArg = 0;
            check(CL12.clSetKernelArg1p(this.slabVmFillDemoSlotsKernel, fillArg++, slotsBuffer),
                    "clSetKernelArg(fill slots output)");
            check(CL12.clSetKernelArg1i(this.slabVmFillDemoSlotsKernel, fillArg, request.n),
                    "clSetKernelArg(fill slots n)");
            PointerBuffer fillWorkSize = stack.callocPointer(1);
            fillWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmFillDemoSlotsKernel, 1,
                    null, fillWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(dfc_slab_vm_fill_demo_slots)");

            int arg = 0;
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, bytecodeBuffer),
                    "clSetKernelArg(generated grid bytecode)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.bytecode.length),
                    "clSetKernelArg(generated grid bytecode length)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, constantsBuffer),
                    "clSetKernelArg(generated grid constants)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.constants.length),
                    "clSetKernelArg(generated grid constant count)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, slotsBuffer),
                    "clSetKernelArg(generated grid slots)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.slotCount),
                    "clSetKernelArg(generated grid slot count)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.n),
                    "clSetKernelArg(generated grid row stride)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockX),
                    "clSetKernelArg(generated grid first x)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockY),
                    "clSetKernelArg(generated grid first y)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockZ),
                    "clSetKernelArg(generated grid first z)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cellWidth),
                    "clSetKernelArg(generated grid cell width)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cellHeight),
                    "clSetKernelArg(generated grid cell height)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cells),
                    "clSetKernelArg(generated grid cells)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ),
                    "clSetKernelArg(generated grid layout)");
            check(CL12.clSetKernelArg1d(this.slabVmCellGridKernel, arg++, request.hoistBase),
                    "clSetKernelArg(generated grid hoist)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, outBuffer),
                    "clSetKernelArg(generated grid output)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg, request.n),
                    "clSetKernelArg(generated grid n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmCellGridKernel, 1,
                    null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(generated dfc_slab_vm_eval_cell_grid)");
            if (readOutput) {
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(generated grid output)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
            }
            check(CL12.clFinish(this.queue), "clFinish(generated grid slab eval)");
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(bytecodeHost);
        }
    }

    synchronized SlabVmResult evalSlabVmCellGridNoiseSlots(SlabVmNoiseCellGridRequest request,
                                                           boolean readOutput) {
        return evalSlabVmCellGridNoiseSlots(request, readOutput, true, false);
    }

    synchronized SlabVmResult evalSlabVmCellGridNoiseSlotsReuseInputs(SlabVmNoiseCellGridRequest request,
                                                                      boolean readOutput) {
        return evalSlabVmCellGridNoiseSlots(request, readOutput, false, false);
    }

    synchronized SlabVmResult evalSlabVmCellGridNoiseSlotsBySlot(SlabVmNoiseCellGridRequest request,
                                                                 boolean readOutput) {
        return evalSlabVmCellGridNoiseSlots(request, readOutput, true, true);
    }

    synchronized SlabVmResult evalSlabVmCellGridNoiseSlotsBySlotReuseInputs(SlabVmNoiseCellGridRequest request,
                                                                            boolean readOutput) {
        return evalSlabVmCellGridNoiseSlots(request, readOutput, false, true);
    }

    private SlabVmResult evalSlabVmCellGridNoiseSlots(SlabVmNoiseCellGridRequest request,
                                                      boolean readOutput, boolean uploadInputs,
                                                      boolean bySlotFill) {
        assertOpen();
        validateNoiseCellGridRequest(request);

        ByteBuffer bytecodeHost = null;
        ByteBuffer permutationsHost = null;
        IntBuffer branchOffsetsHost = null;
        IntBuffer branchCountsHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);

            boolean writeInputs = uploadInputs || !noiseInputsReady(request);
            if (writeInputs) {
                bytecodeHost = MemoryUtil.memAlloc(request.bytecode.length);
                bytecodeHost.put(request.bytecode).flip();
                permutationsHost = MemoryUtil.memAlloc(request.permutations.length);
                permutationsHost.put(request.permutations).flip();
                branchOffsetsHost = MemoryUtil.memAllocInt(request.branchOctaveOffsets.length);
                branchOffsetsHost.put(request.branchOctaveOffsets).flip();
                branchCountsHost = MemoryUtil.memAllocInt(request.branchOctaveCounts.length);
                branchCountsHost.put(request.branchOctaveCounts).flip();
            }
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long bytecodeBuffer = ensureBuffer(this.gridBytecodeBuffer, request.bytecode.length,
                    CL12.CL_MEM_READ_ONLY, err, "noise grid bytecode");
            long constantsBuffer = ensureBuffer(this.gridConstantsBuffer,
                    doubleBytes(request.constants.length), CL12.CL_MEM_READ_ONLY, err, "noise grid constants");
            long slotsBuffer = ensureBuffer(this.gridSlotsBuffer,
                    doubleBytes(request.n * request.slotCount), CL12.CL_MEM_READ_WRITE, err, "noise grid slots");
            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "noise grid output");
            long permutationsBuffer = ensureBuffer(this.noisePermutationsBuffer, request.permutations.length,
                    CL12.CL_MEM_READ_ONLY, err, "noise permutations");
            long originsBuffer = ensureBuffer(this.noiseOriginsBuffer, doubleBytes(request.origins.length),
                    CL12.CL_MEM_READ_ONLY, err, "noise origins");
            long inputFactorsBuffer = ensureBuffer(this.noiseInputFactorsBuffer,
                    doubleBytes(request.inputFactors.length), CL12.CL_MEM_READ_ONLY, err, "noise input factors");
            long ampFactorsBuffer = ensureBuffer(this.noiseAmpFactorsBuffer,
                    doubleBytes(request.ampFactors.length), CL12.CL_MEM_READ_ONLY, err, "noise amp factors");
            long branchOffsetsBuffer = ensureBuffer(this.noiseBranchOctaveOffsetsBuffer,
                    intBytes(request.branchOctaveOffsets.length), CL12.CL_MEM_READ_ONLY, err,
                    "noise branch octave offsets");
            long branchCountsBuffer = ensureBuffer(this.noiseBranchOctaveCountsBuffer,
                    intBytes(request.branchOctaveCounts.length), CL12.CL_MEM_READ_ONLY, err,
                    "noise branch octave counts");
            long branchScalesBuffer = ensureBuffer(this.noiseBranchScalesBuffer,
                    doubleBytes(request.branchCoordScales.length), CL12.CL_MEM_READ_ONLY, err, "noise branch scales");
            long slotFactorsBuffer = ensureBuffer(this.noiseSlotFactorsBuffer,
                    doubleBytes(request.slotValueFactors.length), CL12.CL_MEM_READ_ONLY, err, "noise slot factors");

            if (writeInputs) {
                check(CL12.clEnqueueWriteBuffer(this.queue, bytecodeBuffer, true, 0L, bytecodeHost, null, null),
                        "clEnqueueWriteBuffer(noise grid bytecode)");
                check(CL12.clEnqueueWriteBuffer(this.queue, permutationsBuffer, true, 0L, permutationsHost, null, null),
                        "clEnqueueWriteBuffer(noise permutations)");
                writeDoubleArray(constantsBuffer, request.constants, "clEnqueueWriteBuffer(noise grid constants)");
                writeDoubleArray(originsBuffer, request.origins, "clEnqueueWriteBuffer(noise origins)");
                writeDoubleArray(inputFactorsBuffer, request.inputFactors, "clEnqueueWriteBuffer(noise input factors)");
                writeDoubleArray(ampFactorsBuffer, request.ampFactors, "clEnqueueWriteBuffer(noise amp factors)");
                check(CL12.clEnqueueWriteBuffer(this.queue, branchOffsetsBuffer, true, 0L, branchOffsetsHost,
                        null, null), "clEnqueueWriteBuffer(noise branch octave offsets)");
                check(CL12.clEnqueueWriteBuffer(this.queue, branchCountsBuffer, true, 0L, branchCountsHost,
                        null, null), "clEnqueueWriteBuffer(noise branch octave counts)");
                writeDoubleArray(branchScalesBuffer, request.branchCoordScales,
                        "clEnqueueWriteBuffer(noise branch scales)");
                writeDoubleArray(slotFactorsBuffer, request.slotValueFactors,
                        "clEnqueueWriteBuffer(noise slot factors)");
            }

            long fillKernel = bySlotFill ? this.slabVmFillNoiseSlotsBySlotKernel : this.slabVmFillNoiseSlotsKernel;
            String fillLabel = bySlotFill ? "noise by-slot fill" : "noise fill";
            int fillArg = 0;
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, slotsBuffer),
                    "clSetKernelArg(noise fill slots)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.n),
                    "clSetKernelArg(noise fill n)");
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, permutationsBuffer),
                    "clSetKernelArg(noise permutations)");
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, originsBuffer),
                    "clSetKernelArg(noise origins)");
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, inputFactorsBuffer),
                    "clSetKernelArg(noise input factors)");
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, ampFactorsBuffer),
                    "clSetKernelArg(noise amp factors)");
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, branchOffsetsBuffer),
                    "clSetKernelArg(noise branch octave offsets)");
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, branchCountsBuffer),
                    "clSetKernelArg(noise branch octave counts)");
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, branchScalesBuffer),
                    "clSetKernelArg(noise branch scales)");
            check(CL12.clSetKernelArg1p(fillKernel, fillArg++, slotFactorsBuffer),
                    "clSetKernelArg(noise slot factors)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.slotCount),
                    "clSetKernelArg(noise slot count)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.branchesPerSlot),
                    "clSetKernelArg(noise branches per slot)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.octavesPerBranch),
                    "clSetKernelArg(noise octaves per branch)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.firstBlockX),
                    "clSetKernelArg(noise first x)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.firstBlockY),
                    "clSetKernelArg(noise first y)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.firstBlockZ),
                    "clSetKernelArg(noise first z)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.cellWidth),
                    "clSetKernelArg(noise cell width)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.cellHeight),
                    "clSetKernelArg(noise cell height)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg++, request.cells),
                    "clSetKernelArg(noise cells)");
            check(CL12.clSetKernelArg1i(fillKernel, fillArg, request.layout),
                    "clSetKernelArg(noise layout)");

            PointerBuffer fillWorkSize = stack.callocPointer(1);
            long fillItems = bySlotFill ? (long) request.n * request.slotCount : request.n;
            fillWorkSize.put(0, fillItems);
            check(CL12.clEnqueueNDRangeKernel(this.queue, fillKernel, 1,
                    null, fillWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(" + fillLabel + ")");

            int arg = 0;
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, bytecodeBuffer),
                    "clSetKernelArg(noise grid bytecode)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.bytecode.length),
                    "clSetKernelArg(noise grid bytecode length)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, constantsBuffer),
                    "clSetKernelArg(noise grid constants)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.constants.length),
                    "clSetKernelArg(noise grid constant count)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, slotsBuffer),
                    "clSetKernelArg(noise grid slots)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.slotCount),
                    "clSetKernelArg(noise grid slot count)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.n),
                    "clSetKernelArg(noise grid row stride)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockX),
                    "clSetKernelArg(noise grid first x)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockY),
                    "clSetKernelArg(noise grid first y)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.firstBlockZ),
                    "clSetKernelArg(noise grid first z)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cellWidth),
                    "clSetKernelArg(noise grid cell width)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cellHeight),
                    "clSetKernelArg(noise grid cell height)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.cells),
                    "clSetKernelArg(noise grid cells)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg++, request.layout),
                    "clSetKernelArg(noise grid layout)");
            check(CL12.clSetKernelArg1d(this.slabVmCellGridKernel, arg++, request.hoistBase),
                    "clSetKernelArg(noise grid hoist)");
            check(CL12.clSetKernelArg1p(this.slabVmCellGridKernel, arg++, outBuffer),
                    "clSetKernelArg(noise grid output)");
            check(CL12.clSetKernelArg1i(this.slabVmCellGridKernel, arg, request.n),
                    "clSetKernelArg(noise grid n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmCellGridKernel, 1,
                    null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(noise dfc_slab_vm_eval_cell_grid)");
            if (readOutput) {
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(noise grid output)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
            }
            check(CL12.clFinish(this.queue), "clFinish(noise grid slab eval)");
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(branchCountsHost);
            free(branchOffsetsHost);
            free(permutationsHost);
            free(bytecodeHost);
        }
    }

    synchronized SlabVmResult evalSlabVmCellGridDirectDemo(SlabVmGeneratedCellGridRequest request,
                                                           boolean readOutput) {
        assertOpen();
        validateGeneratedCellGridRequest(request);

        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "direct demo grid output");

            int arg = 0;
            check(CL12.clSetKernelArg1i(this.slabVmDirectDemoKernel, arg++, request.firstBlockX),
                    "clSetKernelArg(direct demo first x)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectDemoKernel, arg++, request.firstBlockY),
                    "clSetKernelArg(direct demo first y)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectDemoKernel, arg++, request.firstBlockZ),
                    "clSetKernelArg(direct demo first z)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectDemoKernel, arg++, request.cellWidth),
                    "clSetKernelArg(direct demo cell width)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectDemoKernel, arg++, request.cellHeight),
                    "clSetKernelArg(direct demo cell height)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectDemoKernel, arg++, request.cells),
                    "clSetKernelArg(direct demo cells)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectDemoKernel, arg++, DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ),
                    "clSetKernelArg(direct demo layout)");
            check(CL12.clSetKernelArg1d(this.slabVmDirectDemoKernel, arg++, request.hoistBase),
                    "clSetKernelArg(direct demo hoist)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectDemoKernel, arg++, outBuffer),
                    "clSetKernelArg(direct demo output)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectDemoKernel, arg, request.n),
                    "clSetKernelArg(direct demo n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmDirectDemoKernel, 1,
                    null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(dfc_slab_vm_eval_cell_grid_direct_demo)");
            if (readOutput) {
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(direct demo grid output)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
            }
            check(CL12.clFinish(this.queue), "clFinish(direct demo grid eval)");
            return new SlabVmResult(System.nanoTime() - started);
        }
    }

    synchronized SlabVmResult evalSlabVmCellGridDirectNoise(SlabVmNoiseCellGridRequest request, int usedSlotCount,
                                                            boolean readOutput) {
        return evalSlabVmCellGridDirectNoise(request, usedSlotCount, readOutput, true);
    }

    synchronized SlabVmResult evalSlabVmCellGridDirectNoiseReuseInputs(SlabVmNoiseCellGridRequest request,
                                                                       int usedSlotCount, boolean readOutput) {
        return evalSlabVmCellGridDirectNoise(request, usedSlotCount, readOutput, false);
    }

    private SlabVmResult evalSlabVmCellGridDirectNoise(SlabVmNoiseCellGridRequest request, int usedSlotCount,
                                                       boolean readOutput, boolean uploadInputs) {
        assertOpen();
        validateNoiseCellGridRequest(request);
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), request.slotCount);

        ByteBuffer permutationsHost = null;
        IntBuffer branchOffsetsHost = null;
        IntBuffer branchCountsHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);

            boolean writeInputs = uploadInputs || !noiseDescriptorInputsReady(request);
            if (writeInputs) {
                permutationsHost = MemoryUtil.memAlloc(request.permutations.length);
                permutationsHost.put(request.permutations).flip();
                branchOffsetsHost = MemoryUtil.memAllocInt(request.branchOctaveOffsets.length);
                branchOffsetsHost.put(request.branchOctaveOffsets).flip();
                branchCountsHost = MemoryUtil.memAllocInt(request.branchOctaveCounts.length);
                branchCountsHost.put(request.branchOctaveCounts).flip();
            }
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "direct noise grid output");
            long permutationsBuffer = ensureBuffer(this.noisePermutationsBuffer, request.permutations.length,
                    CL12.CL_MEM_READ_ONLY, err, "direct noise permutations");
            long originsBuffer = ensureBuffer(this.noiseOriginsBuffer, doubleBytes(request.origins.length),
                    CL12.CL_MEM_READ_ONLY, err, "direct noise origins");
            long inputFactorsBuffer = ensureBuffer(this.noiseInputFactorsBuffer,
                    doubleBytes(request.inputFactors.length), CL12.CL_MEM_READ_ONLY, err, "direct noise input factors");
            long ampFactorsBuffer = ensureBuffer(this.noiseAmpFactorsBuffer,
                    doubleBytes(request.ampFactors.length), CL12.CL_MEM_READ_ONLY, err, "direct noise amp factors");
            long branchOffsetsBuffer = ensureBuffer(this.noiseBranchOctaveOffsetsBuffer,
                    intBytes(request.branchOctaveOffsets.length), CL12.CL_MEM_READ_ONLY, err,
                    "direct noise branch octave offsets");
            long branchCountsBuffer = ensureBuffer(this.noiseBranchOctaveCountsBuffer,
                    intBytes(request.branchOctaveCounts.length), CL12.CL_MEM_READ_ONLY, err,
                    "direct noise branch octave counts");
            long branchScalesBuffer = ensureBuffer(this.noiseBranchScalesBuffer,
                    doubleBytes(request.branchCoordScales.length), CL12.CL_MEM_READ_ONLY, err,
                    "direct noise branch scales");
            long slotFactorsBuffer = ensureBuffer(this.noiseSlotFactorsBuffer,
                    doubleBytes(request.slotValueFactors.length), CL12.CL_MEM_READ_ONLY, err,
                    "direct noise slot factors");

            if (writeInputs) {
                check(CL12.clEnqueueWriteBuffer(this.queue, permutationsBuffer, true, 0L, permutationsHost,
                        null, null), "clEnqueueWriteBuffer(direct noise permutations)");
                writeDoubleArray(originsBuffer, request.origins, "clEnqueueWriteBuffer(direct noise origins)");
                writeDoubleArray(inputFactorsBuffer, request.inputFactors,
                        "clEnqueueWriteBuffer(direct noise input factors)");
                writeDoubleArray(ampFactorsBuffer, request.ampFactors,
                        "clEnqueueWriteBuffer(direct noise amp factors)");
                check(CL12.clEnqueueWriteBuffer(this.queue, branchOffsetsBuffer, true, 0L, branchOffsetsHost,
                        null, null), "clEnqueueWriteBuffer(direct noise branch octave offsets)");
                check(CL12.clEnqueueWriteBuffer(this.queue, branchCountsBuffer, true, 0L, branchCountsHost,
                        null, null), "clEnqueueWriteBuffer(direct noise branch octave counts)");
                writeDoubleArray(branchScalesBuffer, request.branchCoordScales,
                        "clEnqueueWriteBuffer(direct noise branch scales)");
                writeDoubleArray(slotFactorsBuffer, request.slotValueFactors,
                        "clEnqueueWriteBuffer(direct noise slot factors)");
            }

            int arg = 0;
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, permutationsBuffer),
                    "clSetKernelArg(direct noise permutations)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, originsBuffer),
                    "clSetKernelArg(direct noise origins)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, inputFactorsBuffer),
                    "clSetKernelArg(direct noise input factors)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, ampFactorsBuffer),
                    "clSetKernelArg(direct noise amp factors)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, branchOffsetsBuffer),
                    "clSetKernelArg(direct noise branch octave offsets)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, branchCountsBuffer),
                    "clSetKernelArg(direct noise branch octave counts)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, branchScalesBuffer),
                    "clSetKernelArg(direct noise branch scales)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, slotFactorsBuffer),
                    "clSetKernelArg(direct noise slot factors)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.slotCount),
                    "clSetKernelArg(direct noise slot count)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.branchesPerSlot),
                    "clSetKernelArg(direct noise branches per slot)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.octavesPerBranch),
                    "clSetKernelArg(direct noise octaves per branch)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, safeUsedSlots),
                    "clSetKernelArg(direct noise used slots)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.firstBlockX),
                    "clSetKernelArg(direct noise first x)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.firstBlockY),
                    "clSetKernelArg(direct noise first y)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.firstBlockZ),
                    "clSetKernelArg(direct noise first z)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.cellWidth),
                    "clSetKernelArg(direct noise cell width)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.cellHeight),
                    "clSetKernelArg(direct noise cell height)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.cells),
                    "clSetKernelArg(direct noise cells)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg++, request.layout),
                    "clSetKernelArg(direct noise layout)");
            check(CL12.clSetKernelArg1d(this.slabVmDirectNoiseKernel, arg++, request.hoistBase),
                    "clSetKernelArg(direct noise hoist)");
            check(CL12.clSetKernelArg1p(this.slabVmDirectNoiseKernel, arg++, outBuffer),
                    "clSetKernelArg(direct noise output)");
            check(CL12.clSetKernelArg1i(this.slabVmDirectNoiseKernel, arg, request.n),
                    "clSetKernelArg(direct noise n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmDirectNoiseKernel, 1,
                    null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(dfc_slab_vm_eval_cell_grid_direct_noise)");
            if (readOutput) {
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(direct noise grid output)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
            }
            check(CL12.clFinish(this.queue), "clFinish(direct noise grid eval)");
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(branchCountsHost);
            free(branchOffsetsHost);
            free(permutationsHost);
        }
    }

    synchronized GeneratedNoiseKernel compileGeneratedNoiseKernel(String kernelSource) {
        assertOpen();
        ByteBuffer sourceBuffer = null;
        long generatedProgram = 0L;
        long generatedKernel = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            sourceBuffer = MemoryUtil.memUTF8(DfcOpenClSources.runtimeSource() + "\n" + kernelSource, true);
            PointerBuffer sources = stack.callocPointer(1);
            sources.put(0, sourceBuffer);
            generatedProgram = CL12.clCreateProgramWithSource(this.context, sources, null, err);
            check(err.get(0), "clCreateProgramWithSource(generated noise)");

            int buildError = CL12.clBuildProgram(generatedProgram, this.candidate.device(),
                    "-cl-std=CL1.2", null, 0L);
            String buildLog = getBuildLog(generatedProgram, this.candidate.device());
            int buildStatus = getBuildStatus(generatedProgram, this.candidate.device());
            if (buildStatus != CL12.CL_BUILD_SUCCESS) {
                throw new IllegalStateException("OpenCL generated noise build failed: " + trimBuildLog(buildLog)
                        + " (error " + buildError + ")");
            }
            check(buildError, "clBuildProgram(generated noise)");

            generatedKernel = CL12.clCreateKernel(generatedProgram, DfcOpenClGeneratedNoiseSource.KERNEL_NAME, err);
            check(err.get(0), "clCreateKernel(" + DfcOpenClGeneratedNoiseSource.KERNEL_NAME + ")");
            return new GeneratedNoiseKernel(generatedProgram, generatedKernel, trimBuildLog(buildLog));
        } catch (Throwable throwable) {
            releaseKernel(generatedKernel);
            releaseProgram(generatedProgram);
            throw throwable;
        } finally {
            if (sourceBuffer != null) {
                MemoryUtil.memFree(sourceBuffer);
            }
        }
    }

    synchronized GeneratedNoiseKernel compileGeneratedNoiseKernelCached(String kernelSource) {
        assertOpen();
        GeneratedNoiseKernel cached = this.generatedKernelCache.get(kernelSource);
        if (cached != null && !cached.closed) {
            return cached;
        }
        GeneratedNoiseKernel compiled = compileGeneratedNoiseKernel(kernelSource);
        this.generatedKernelCache.put(kernelSource, compiled);
        return compiled;
    }

    synchronized SlabVmResult evalGeneratedNoiseKernel(GeneratedNoiseKernel generated,
                                                       SlabVmNoiseCellGridRequest request,
                                                       boolean readOutput) {
        return evalGeneratedNoiseKernel(generated, request, null, readOutput, true, true);
    }

    synchronized SlabVmResult evalGeneratedNoiseKernelReuseInputs(GeneratedNoiseKernel generated,
                                                                  SlabVmNoiseCellGridRequest request,
                                                                  boolean readOutput) {
        return evalGeneratedNoiseKernel(generated, request, null, readOutput, false, true);
    }

    synchronized SlabVmResult evalGeneratedNoiseKernel(GeneratedNoiseKernel generated,
                                                       SlabVmNoiseCellGridRequest request,
                                                       double[] externalSlots,
                                                       boolean readOutput) {
        return evalGeneratedNoiseKernel(generated, request, externalSlots, readOutput, true, true);
    }

    synchronized SlabVmResult evalGeneratedNoiseKernelReuseInputs(GeneratedNoiseKernel generated,
                                                                  SlabVmNoiseCellGridRequest request,
                                                                  double[] externalSlots,
                                                                  boolean readOutput) {
        return evalGeneratedNoiseKernel(generated, request, externalSlots, readOutput, false, true);
    }

    private SlabVmResult evalGeneratedNoiseKernel(GeneratedNoiseKernel generated,
                                                  SlabVmNoiseCellGridRequest request,
                                                  double[] externalSlots,
                                                  boolean readOutput,
                                                  boolean uploadInputs,
                                                  boolean bindExternalSlots) {
        assertOpen();
        generated.assertOpen();
        validateNoiseCellGridRequest(request);
        if (bindExternalSlots && externalSlots != null
                && externalSlots.length < Math.multiplyExact(request.n, request.slotCount)) {
            throw new IllegalArgumentException("external slot buffer is shorter than n * slotCount");
        }

        ByteBuffer permutationsHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            boolean writeInputs = uploadInputs
                    || !bufferReady(this.noisePermutationsBuffer, request.permutations.length, CL12.CL_MEM_READ_ONLY);
            if (writeInputs) {
                permutationsHost = MemoryUtil.memAlloc(request.permutations.length);
                permutationsHost.put(request.permutations).flip();
            }
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long permutationsBuffer = ensureBuffer(this.noisePermutationsBuffer, request.permutations.length,
                    CL12.CL_MEM_READ_ONLY, err, "generated source noise permutations");
            long externalSlotsBuffer = 0L;
            double[] externalSlotsForKernel = null;
            boolean writeExternalSlots = false;
            if (bindExternalSlots) {
                externalSlotsForKernel = externalSlots == null || externalSlots.length == 0
                        ? EMPTY_EXTERNAL_SLOTS
                        : externalSlots;
                long externalSlotBytes = doubleBytes(externalSlotsForKernel.length);
                writeExternalSlots = uploadInputs
                        || !bufferReady(this.generatedExternalSlotsBuffer, externalSlotBytes, CL12.CL_MEM_READ_ONLY);
                externalSlotsBuffer = ensureBuffer(this.generatedExternalSlotsBuffer,
                        externalSlotBytes, CL12.CL_MEM_READ_ONLY, err,
                        "generated source external slots");
            }
            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "generated source noise output");
            if (writeInputs) {
                check(CL12.clEnqueueWriteBuffer(this.queue, permutationsBuffer, true, 0L, permutationsHost,
                        null, null), "clEnqueueWriteBuffer(generated source noise permutations)");
            }
            if (writeExternalSlots) {
                writeDoubleArray(externalSlotsBuffer, externalSlotsForKernel,
                        "clEnqueueWriteBuffer(generated source external slots)");
            }

            int arg = 0;
            check(CL12.clSetKernelArg1p(generated.kernel, arg++, permutationsBuffer),
                    "clSetKernelArg(generated source noise permutations)");
            if (bindExternalSlots) {
                check(CL12.clSetKernelArg1p(generated.kernel, arg++, externalSlotsBuffer),
                        "clSetKernelArg(generated source external slots)");
            }
            check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockX),
                    "clSetKernelArg(generated source noise first x)");
            check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockY),
                    "clSetKernelArg(generated source noise first y)");
            check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockZ),
                    "clSetKernelArg(generated source noise first z)");
            check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cellWidth),
                    "clSetKernelArg(generated source noise cell width)");
            check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cellHeight),
                    "clSetKernelArg(generated source noise cell height)");
            check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cells),
                    "clSetKernelArg(generated source noise cells)");
            check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.layout),
                    "clSetKernelArg(generated source noise layout)");
            check(CL12.clSetKernelArg1d(generated.kernel, arg++, request.hoistBase),
                    "clSetKernelArg(generated source noise hoist)");
            check(CL12.clSetKernelArg1p(generated.kernel, arg++, outBuffer),
                    "clSetKernelArg(generated source noise output)");
            check(CL12.clSetKernelArg1i(generated.kernel, arg, request.n),
                    "clSetKernelArg(generated source noise n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            check(CL12.clEnqueueNDRangeKernel(this.queue, generated.kernel, 1,
                    null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(generated source noise)");
            if (readOutput) {
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(generated source noise output)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
            }
            check(CL12.clFinish(this.queue), "clFinish(generated source noise)");
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(permutationsHost);
        }
    }

    synchronized SlabVmResult evalGeneratedNoiseKernelWavesToSlotBuffer(GeneratedNoiseKernel[] generatedKernels,
                                                                         boolean[][] waves,
                                                                         SlabVmNoiseCellGridRequest request,
                                                                         int slotBufferSlotCount,
                                                                         boolean uploadInputs) {
        return evalGeneratedNoiseKernelWavesToSlotBuffer(
                generatedKernels, waves, request, slotBufferSlotCount, uploadInputs, null);
    }

    synchronized SlabVmResult evalGeneratedNoiseKernelWavesToSlotBuffer(GeneratedNoiseKernel[] generatedKernels,
                                                                         boolean[][] waves,
                                                                         SlabVmNoiseCellGridRequest request,
                                                                         int slotBufferSlotCount,
                                                                         boolean uploadInputs,
                                                                         double[] slotBufferOut) {
        assertOpen();
        validateNoiseCellGridRequest(request);
        if (slotBufferSlotCount <= 0) {
            throw new IllegalArgumentException("slotBufferSlotCount must be positive");
        }
        int slotValues = Math.multiplyExact(request.n, slotBufferSlotCount);
        if (slotBufferOut != null && slotBufferOut.length < slotValues) {
            throw new IllegalArgumentException("slotBufferOut is shorter than n * slotBufferSlotCount");
        }

        ByteBuffer permutationsHost = null;
        DoubleBuffer slotOutHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            boolean writeInputs = uploadInputs
                    || !bufferReady(this.noisePermutationsBuffer, request.permutations.length, CL12.CL_MEM_READ_ONLY);
            if (writeInputs) {
                permutationsHost = MemoryUtil.memAlloc(request.permutations.length);
                permutationsHost.put(request.permutations).flip();
            }
            if (slotBufferOut != null) {
                slotOutHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, slotValues);
            }

            long permutationsBuffer = ensureBuffer(this.noisePermutationsBuffer, request.permutations.length,
                    CL12.CL_MEM_READ_ONLY, err, "generated wave permutations");
            long slotBuffer = ensureBuffer(this.generatedSlotBuffer, doubleBytes(slotValues),
                    CL12.CL_MEM_READ_WRITE, err, "generated wave slots");
            if (writeInputs) {
                check(CL12.clEnqueueWriteBuffer(this.queue, permutationsBuffer, true, 0L, permutationsHost,
                        null, null), "clEnqueueWriteBuffer(generated wave permutations)");
            }

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            for (boolean[] wave : waves) {
                if (wave == null) {
                    continue;
                }
                int limit = Math.min(wave.length, generatedKernels.length);
                for (int chunk = 0; chunk < limit; chunk++) {
                    if (!wave[chunk]) {
                        continue;
                    }
                    GeneratedNoiseKernel generated = generatedKernels[chunk];
                    if (generated == null) {
                        throw new IllegalArgumentException("missing generated kernel for chunk " + chunk);
                    }
                    generated.assertOpen();
                    int arg = 0;
                    check(CL12.clSetKernelArg1p(generated.kernel, arg++, permutationsBuffer),
                            "clSetKernelArg(generated wave permutations)");
                    check(CL12.clSetKernelArg1p(generated.kernel, arg++, slotBuffer),
                            "clSetKernelArg(generated wave external slots)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockX),
                            "clSetKernelArg(generated wave first x)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockY),
                            "clSetKernelArg(generated wave first y)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockZ),
                            "clSetKernelArg(generated wave first z)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cellWidth),
                            "clSetKernelArg(generated wave cell width)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cellHeight),
                            "clSetKernelArg(generated wave cell height)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cells),
                            "clSetKernelArg(generated wave cells)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.layout),
                            "clSetKernelArg(generated wave layout)");
                    check(CL12.clSetKernelArg1d(generated.kernel, arg++, request.hoistBase),
                            "clSetKernelArg(generated wave hoist)");
                    check(CL12.clSetKernelArg1p(generated.kernel, arg++, slotBuffer),
                            "clSetKernelArg(generated wave output slots)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg, request.n),
                            "clSetKernelArg(generated wave n)");
                    check(CL12.clEnqueueNDRangeKernel(this.queue, generated.kernel, 1,
                            null, globalWorkSize, null, null, null),
                            "clEnqueueNDRangeKernel(generated wave)");
                }
            }
            if (slotBufferOut != null) {
                slotOutHost.clear();
                slotOutHost.limit(slotValues);
                check(CL12.clEnqueueReadBuffer(this.queue, slotBuffer, true, 0L, slotOutHost, null, null),
                        "clEnqueueReadBuffer(generated wave slots)");
                slotOutHost.position(0);
                slotOutHost.get(slotBufferOut, 0, slotValues);
            }
            check(CL12.clFinish(this.queue), "clFinish(generated wave)");
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(permutationsHost);
        }
    }

    synchronized SlabVmResult evalGeneratedNoiseKernelWavesToFinalOutput(GeneratedNoiseKernel[] waveKernels,
                                                                          boolean[][] waves,
                                                                          GeneratedNoiseKernel finalKernel,
                                                                          SlabVmNoiseCellGridRequest request,
                                                                          int slotBufferSlotCount,
                                                                          boolean uploadInputs,
                                                                          double[] initialSlotBuffer,
                                                                          boolean readOutput) {
        assertOpen();
        validateNoiseCellGridRequest(request);
        finalKernel.assertOpen();
        if (slotBufferSlotCount <= 0) {
            throw new IllegalArgumentException("slotBufferSlotCount must be positive");
        }
        int slotValues = Math.multiplyExact(request.n, slotBufferSlotCount);
        if (initialSlotBuffer != null && initialSlotBuffer.length < slotValues) {
            throw new IllegalArgumentException("initialSlotBuffer is shorter than n * slotBufferSlotCount");
        }

        ByteBuffer permutationsHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            boolean writeInputs = uploadInputs
                    || !bufferReady(this.noisePermutationsBuffer, request.permutations.length, CL12.CL_MEM_READ_ONLY);
            if (writeInputs) {
                permutationsHost = MemoryUtil.memAlloc(request.permutations.length);
                permutationsHost.put(request.permutations).flip();
            }
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long permutationsBuffer = ensureBuffer(this.noisePermutationsBuffer, request.permutations.length,
                    CL12.CL_MEM_READ_ONLY, err, "generated final output permutations");
            long slotBufferBytes = doubleBytes(slotValues);
            boolean writeInitialSlots = initialSlotBuffer != null
                    && (uploadInputs || !bufferReady(this.generatedSlotBuffer, slotBufferBytes,
                    CL12.CL_MEM_READ_WRITE));
            long slotBuffer = ensureBuffer(this.generatedSlotBuffer, slotBufferBytes,
                    CL12.CL_MEM_READ_WRITE, err, "generated final output slots");
            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "generated final output");
            if (writeInputs) {
                check(CL12.clEnqueueWriteBuffer(this.queue, permutationsBuffer, true, 0L, permutationsHost,
                        null, null), "clEnqueueWriteBuffer(generated final output permutations)");
            }
            if (writeInitialSlots) {
                writeDoubleArray(slotBuffer, initialSlotBuffer,
                        "clEnqueueWriteBuffer(generated final output initial slots)");
            }

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            for (boolean[] wave : waves) {
                if (wave == null) {
                    continue;
                }
                int limit = Math.min(wave.length, waveKernels.length);
                for (int chunk = 0; chunk < limit; chunk++) {
                    if (!wave[chunk]) {
                        continue;
                    }
                    GeneratedNoiseKernel generated = waveKernels[chunk];
                    if (generated == null) {
                        throw new IllegalArgumentException("missing generated kernel for chunk " + chunk);
                    }
                    generated.assertOpen();
                    int arg = 0;
                    check(CL12.clSetKernelArg1p(generated.kernel, arg++, permutationsBuffer),
                            "clSetKernelArg(generated final wave permutations)");
                    check(CL12.clSetKernelArg1p(generated.kernel, arg++, slotBuffer),
                            "clSetKernelArg(generated final wave external slots)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockX),
                            "clSetKernelArg(generated final wave first x)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockY),
                            "clSetKernelArg(generated final wave first y)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockZ),
                            "clSetKernelArg(generated final wave first z)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cellWidth),
                            "clSetKernelArg(generated final wave cell width)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cellHeight),
                            "clSetKernelArg(generated final wave cell height)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cells),
                            "clSetKernelArg(generated final wave cells)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.layout),
                            "clSetKernelArg(generated final wave layout)");
                    check(CL12.clSetKernelArg1d(generated.kernel, arg++, request.hoistBase),
                            "clSetKernelArg(generated final wave hoist)");
                    check(CL12.clSetKernelArg1p(generated.kernel, arg++, slotBuffer),
                            "clSetKernelArg(generated final wave output slots)");
                    check(CL12.clSetKernelArg1i(generated.kernel, arg, request.n),
                            "clSetKernelArg(generated final wave n)");
                    check(CL12.clEnqueueNDRangeKernel(this.queue, generated.kernel, 1,
                            null, globalWorkSize, null, null, null),
                            "clEnqueueNDRangeKernel(generated final wave)");
                }
            }

            int arg = 0;
            check(CL12.clSetKernelArg1p(finalKernel.kernel, arg++, permutationsBuffer),
                    "clSetKernelArg(generated final output permutations)");
            check(CL12.clSetKernelArg1p(finalKernel.kernel, arg++, slotBuffer),
                    "clSetKernelArg(generated final output external slots)");
            check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.firstBlockX),
                    "clSetKernelArg(generated final output first x)");
            check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.firstBlockY),
                    "clSetKernelArg(generated final output first y)");
            check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.firstBlockZ),
                    "clSetKernelArg(generated final output first z)");
            check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.cellWidth),
                    "clSetKernelArg(generated final output cell width)");
            check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.cellHeight),
                    "clSetKernelArg(generated final output cell height)");
            check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.cells),
                    "clSetKernelArg(generated final output cells)");
            check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.layout),
                    "clSetKernelArg(generated final output layout)");
            check(CL12.clSetKernelArg1d(finalKernel.kernel, arg++, request.hoistBase),
                    "clSetKernelArg(generated final output hoist)");
            check(CL12.clSetKernelArg1p(finalKernel.kernel, arg++, outBuffer),
                    "clSetKernelArg(generated final output output)");
            check(CL12.clSetKernelArg1i(finalKernel.kernel, arg, request.n),
                    "clSetKernelArg(generated final output n)");
            check(CL12.clEnqueueNDRangeKernel(this.queue, finalKernel.kernel, 1,
                    null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(generated final output)");

            if (readOutput) {
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(generated final output)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
            }
            check(CL12.clFinish(this.queue), "clFinish(generated final output)");
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(permutationsHost);
        }
    }

    synchronized SlabVmResult evalFinalOutputStagesToFinalOutput(FinalOutputStage[] stages,
                                                                 GeneratedNoiseKernel finalKernel,
                                                                 SlabVmNoiseCellGridRequest request,
                                                                 int slotBufferSlotCount,
                                                                 boolean uploadInputs,
                                                                 double[] initialSlotBuffer,
                                                                 GeneratedNoiseKernel flatCache2dKernel,
                                                                 FlatCache2dPrefill flatCache2dPrefill,
                                                                 boolean readOutput) {
        assertOpen();
        validateNoiseCellGridRequest(request);
        finalKernel.assertOpen();
        if (slotBufferSlotCount <= 0) {
            throw new IllegalArgumentException("slotBufferSlotCount must be positive");
        }
        int slotValues = Math.multiplyExact(request.n, slotBufferSlotCount);
        if (initialSlotBuffer != null && initialSlotBuffer.length < slotValues) {
            throw new IllegalArgumentException("initialSlotBuffer is shorter than n * slotBufferSlotCount");
        }

        ByteBuffer permutationsHost = null;
        DoubleBuffer outHost = null;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            boolean writeInputs = uploadInputs
                    || !bufferReady(this.noisePermutationsBuffer, request.permutations.length, CL12.CL_MEM_READ_ONLY);
            if (writeInputs) {
                permutationsHost = MemoryUtil.memAlloc(request.permutations.length);
                permutationsHost.put(request.permutations).flip();
            }
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long permutationsBuffer = ensureBuffer(this.noisePermutationsBuffer, request.permutations.length,
                    CL12.CL_MEM_READ_ONLY, err, "generated final output permutations");
            long slotBufferBytes = doubleBytes(slotValues);
            boolean writeInitialSlots = initialSlotBuffer != null
                    && (uploadInputs || !bufferReady(this.generatedSlotBuffer, slotBufferBytes,
                    CL12.CL_MEM_READ_WRITE));
            long slotBuffer = ensureBuffer(this.generatedSlotBuffer, slotBufferBytes,
                    CL12.CL_MEM_READ_WRITE, err, "generated final output slots");
            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "generated final output");
            if (writeInputs) {
                check(CL12.clEnqueueWriteBuffer(this.queue, permutationsBuffer, true, 0L, permutationsHost,
                        null, null), "clEnqueueWriteBuffer(generated final output permutations)");
            }
            if (writeInitialSlots) {
                writeDoubleArray(slotBuffer, initialSlotBuffer,
                        "clEnqueueWriteBuffer(generated final output initial slots)");
            }

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            if (flatCache2dKernel != null && flatCache2dPrefill != null) {
                enqueueFlatCache2dPrefill(flatCache2dKernel, request, flatCache2dPrefill,
                        slotBuffer, globalWorkSize, err);
            }
            if (stages != null) {
                for (FinalOutputStage stage : stages) {
                    if (stage == null) {
                        continue;
                    }
                    if (stage.generated()) {
                        enqueueGeneratedFinalOutputStage(stage.generatedKernel(), request,
                                permutationsBuffer, slotBuffer, globalWorkSize);
                    } else {
                        enqueueSlabVmFinalOutputStage(stage, request, slotBufferSlotCount, slotBuffer,
                                globalWorkSize, err);
                    }
                }
            }

            enqueueGeneratedFinalOutputKernel(finalKernel, request, permutationsBuffer, slotBuffer,
                    outBuffer, globalWorkSize);

            if (readOutput) {
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(generated final output)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
            }
            check(CL12.clFinish(this.queue), "clFinish(generated final output)");
            return new SlabVmResult(System.nanoTime() - started);
        } finally {
            free(permutationsHost);
        }
    }

    synchronized FinalOutputTraceResult evalFinalOutputStagesToFinalOutputTrace(FinalOutputStage[] stages,
                                                                                GeneratedNoiseKernel finalKernel,
                                                                                SlabVmNoiseCellGridRequest request,
                                                                                int slotBufferSlotCount,
                                                                                boolean uploadInputs,
                                                                                double[] initialSlotBuffer,
                                                                                GeneratedNoiseKernel flatCache2dKernel,
                                                                                FlatCache2dPrefill flatCache2dPrefill,
                                                                                boolean readOutput) {
        assertOpen();
        validateNoiseCellGridRequest(request);
        finalKernel.assertOpen();
        if (slotBufferSlotCount <= 0) {
            throw new IllegalArgumentException("slotBufferSlotCount must be positive");
        }
        int slotValues = Math.multiplyExact(request.n, slotBufferSlotCount);
        if (initialSlotBuffer != null && initialSlotBuffer.length < slotValues) {
            throw new IllegalArgumentException("initialSlotBuffer is shorter than n * slotBufferSlotCount");
        }

        ByteBuffer permutationsHost = null;
        DoubleBuffer outHost = null;
        int stageCount = stages == null ? 0 : stages.length;
        long[] stageNanos = new long[stageCount];
        long[] stageSubmitNanos = new long[stageCount];
        long[] stageWaitNanos = new long[stageCount];
        long inputWriteNanos = 0L;
        long initialSlotWriteNanos = 0L;
        long finalKernelNanos = 0L;
        long finalSubmitNanos = 0L;
        long finalWaitNanos = 0L;
        long readbackNanos = 0L;
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            boolean writeInputs = uploadInputs
                    || !bufferReady(this.noisePermutationsBuffer, request.permutations.length, CL12.CL_MEM_READ_ONLY);
            if (writeInputs) {
                permutationsHost = MemoryUtil.memAlloc(request.permutations.length);
                permutationsHost.put(request.permutations).flip();
            }
            if (readOutput) {
                outHost = ensureHostDoubleBuffer(this.gridOutHostBuffer, request.n);
            }

            long permutationsBuffer = ensureBuffer(this.noisePermutationsBuffer, request.permutations.length,
                    CL12.CL_MEM_READ_ONLY, err, "generated final output trace permutations");
            long slotBufferBytes = doubleBytes(slotValues);
            boolean writeInitialSlots = initialSlotBuffer != null
                    && (uploadInputs || !bufferReady(this.generatedSlotBuffer, slotBufferBytes,
                    CL12.CL_MEM_READ_WRITE));
            long slotBuffer = ensureBuffer(this.generatedSlotBuffer, slotBufferBytes,
                    CL12.CL_MEM_READ_WRITE, err, "generated final output trace slots");
            long outBuffer = ensureBuffer(this.gridOutBuffer,
                    doubleBytes(request.n), CL12.CL_MEM_WRITE_ONLY, err, "generated final output trace");
            if (writeInputs) {
                long writeStarted = System.nanoTime();
                check(CL12.clEnqueueWriteBuffer(this.queue, permutationsBuffer, true, 0L, permutationsHost,
                        null, null), "clEnqueueWriteBuffer(generated final output trace permutations)");
                inputWriteNanos += System.nanoTime() - writeStarted;
            }
            if (writeInitialSlots) {
                long writeStarted = System.nanoTime();
                writeDoubleArray(slotBuffer, initialSlotBuffer,
                        "clEnqueueWriteBuffer(generated final output trace initial slots)");
                initialSlotWriteNanos += System.nanoTime() - writeStarted;
            }

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, request.n);
            if (flatCache2dKernel != null && flatCache2dPrefill != null) {
                enqueueFlatCache2dPrefill(flatCache2dKernel, request, flatCache2dPrefill,
                        slotBuffer, globalWorkSize, err);
            }
            if (stages != null) {
                for (int i = 0; i < stages.length; i++) {
                    FinalOutputStage stage = stages[i];
                    if (stage == null) {
                        continue;
                    }
                    long stageStarted = System.nanoTime();
                    long submitStarted = System.nanoTime();
                    if (stage.generated()) {
                        enqueueGeneratedFinalOutputStage(stage.generatedKernel(), request,
                                permutationsBuffer, slotBuffer, globalWorkSize);
                    } else {
                        enqueueSlabVmFinalOutputStage(stage, request, slotBufferSlotCount, slotBuffer,
                                globalWorkSize, err);
                    }
                    stageSubmitNanos[i] = System.nanoTime() - submitStarted;
                    long waitStarted = System.nanoTime();
                    check(CL12.clFinish(this.queue), "clFinish(generated final output trace stage)");
                    stageWaitNanos[i] = System.nanoTime() - waitStarted;
                    stageNanos[i] = System.nanoTime() - stageStarted;
                }
            }

            long finalStarted = System.nanoTime();
            long finalSubmitStarted = System.nanoTime();
            enqueueGeneratedFinalOutputKernel(finalKernel, request, permutationsBuffer, slotBuffer,
                    outBuffer, globalWorkSize);
            finalSubmitNanos = System.nanoTime() - finalSubmitStarted;
            long finalWaitStarted = System.nanoTime();
            check(CL12.clFinish(this.queue), "clFinish(generated final output trace final)");
            finalWaitNanos = System.nanoTime() - finalWaitStarted;
            finalKernelNanos = System.nanoTime() - finalStarted;

            if (readOutput) {
                long readStarted = System.nanoTime();
                outHost.clear();
                outHost.limit(request.n);
                check(CL12.clEnqueueReadBuffer(this.queue, outBuffer, true, 0L, outHost, null, null),
                        "clEnqueueReadBuffer(generated final output trace)");
                outHost.position(0);
                outHost.get(request.out, 0, request.n);
                readbackNanos = System.nanoTime() - readStarted;
            }
            return new FinalOutputTraceResult(
                    System.nanoTime() - started, inputWriteNanos, initialSlotWriteNanos,
                    stageNanos, stageSubmitNanos, stageWaitNanos,
                    finalKernelNanos, finalSubmitNanos, finalWaitNanos, readbackNanos);
        } finally {
            free(permutationsHost);
        }
    }

    private void enqueueFlatCache2dPrefill(GeneratedNoiseKernel kernel,
                                           SlabVmNoiseCellGridRequest request,
                                           FlatCache2dPrefill prefill,
                                           long slotBuffer,
                                           PointerBuffer globalWorkSize,
                                           IntBuffer err) {
        kernel.assertOpen();
        if (prefill.slotCount() <= 0) {
            return;
        }

        long valuesBuffer = ensureBuffer(this.flatCache2dValuesBuffer,
                doubleBytes(prefill.flatValues().length), CL12.CL_MEM_READ_ONLY, err, "FlatCache 2D values");
        long slotCompactIndicesBuffer = ensureBuffer(this.flatCache2dSlotCompactIndicesBuffer,
                intBytes(prefill.slotCompactIndices().length), CL12.CL_MEM_READ_ONLY, err,
                "FlatCache 2D slot compact indices");
        long slotTableIndicesBuffer = ensureBuffer(this.flatCache2dSlotTableIndicesBuffer,
                intBytes(prefill.slotTableIndices().length), CL12.CL_MEM_READ_ONLY, err,
                "FlatCache 2D slot table indices");
        long tableOffsetsBuffer = ensureBuffer(this.flatCache2dTableOffsetsBuffer,
                intBytes(prefill.tableOffsets().length), CL12.CL_MEM_READ_ONLY, err,
                "FlatCache 2D table offsets");
        long tableSidesBuffer = ensureBuffer(this.flatCache2dTableSidesBuffer,
                intBytes(prefill.tableSides().length), CL12.CL_MEM_READ_ONLY, err,
                "FlatCache 2D table sides");
        long tableFirstNoiseXBuffer = ensureBuffer(this.flatCache2dTableFirstNoiseXBuffer,
                intBytes(prefill.tableFirstNoiseX().length), CL12.CL_MEM_READ_ONLY, err,
                "FlatCache 2D table first noise x");
        long tableFirstNoiseZBuffer = ensureBuffer(this.flatCache2dTableFirstNoiseZBuffer,
                intBytes(prefill.tableFirstNoiseZ().length), CL12.CL_MEM_READ_ONLY, err,
                "FlatCache 2D table first noise z");

        writeDoubleArray(valuesBuffer, prefill.flatValues(), "clEnqueueWriteBuffer(FlatCache 2D values)");
        writeIntArray(slotCompactIndicesBuffer, prefill.slotCompactIndices(),
                "clEnqueueWriteBuffer(FlatCache 2D slot compact indices)");
        writeIntArray(slotTableIndicesBuffer, prefill.slotTableIndices(),
                "clEnqueueWriteBuffer(FlatCache 2D slot table indices)");
        writeIntArray(tableOffsetsBuffer, prefill.tableOffsets(), "clEnqueueWriteBuffer(FlatCache 2D table offsets)");
        writeIntArray(tableSidesBuffer, prefill.tableSides(), "clEnqueueWriteBuffer(FlatCache 2D table sides)");
        writeIntArray(tableFirstNoiseXBuffer, prefill.tableFirstNoiseX(),
                "clEnqueueWriteBuffer(FlatCache 2D table first noise x)");
        writeIntArray(tableFirstNoiseZBuffer, prefill.tableFirstNoiseZ(),
                "clEnqueueWriteBuffer(FlatCache 2D table first noise z)");

        int arg = 0;
        check(CL12.clSetKernelArg1p(kernel.kernel, arg++, valuesBuffer),
                "clSetKernelArg(FlatCache 2D values)");
        check(CL12.clSetKernelArg1p(kernel.kernel, arg++, slotCompactIndicesBuffer),
                "clSetKernelArg(FlatCache 2D slot compact indices)");
        check(CL12.clSetKernelArg1p(kernel.kernel, arg++, slotTableIndicesBuffer),
                "clSetKernelArg(FlatCache 2D slot table indices)");
        check(CL12.clSetKernelArg1p(kernel.kernel, arg++, tableOffsetsBuffer),
                "clSetKernelArg(FlatCache 2D table offsets)");
        check(CL12.clSetKernelArg1p(kernel.kernel, arg++, tableSidesBuffer),
                "clSetKernelArg(FlatCache 2D table sides)");
        check(CL12.clSetKernelArg1p(kernel.kernel, arg++, tableFirstNoiseXBuffer),
                "clSetKernelArg(FlatCache 2D table first noise x)");
        check(CL12.clSetKernelArg1p(kernel.kernel, arg++, tableFirstNoiseZBuffer),
                "clSetKernelArg(FlatCache 2D table first noise z)");
        check(CL12.clSetKernelArg1p(kernel.kernel, arg++, slotBuffer),
                "clSetKernelArg(FlatCache 2D slot buffer)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg++, request.firstBlockX),
                "clSetKernelArg(FlatCache 2D first x)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg++, request.firstBlockY),
                "clSetKernelArg(FlatCache 2D first y)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg++, request.firstBlockZ),
                "clSetKernelArg(FlatCache 2D first z)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg++, request.cellWidth),
                "clSetKernelArg(FlatCache 2D cell width)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg++, request.cellHeight),
                "clSetKernelArg(FlatCache 2D cell height)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg++, request.cells),
                "clSetKernelArg(FlatCache 2D cells)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg++, request.layout),
                "clSetKernelArg(FlatCache 2D layout)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg++, prefill.slotCount()),
                "clSetKernelArg(FlatCache 2D slot count)");
        check(CL12.clSetKernelArg1i(kernel.kernel, arg, request.n),
                "clSetKernelArg(FlatCache 2D n)");
        check(CL12.clEnqueueNDRangeKernel(this.queue, kernel.kernel, 1,
                null, globalWorkSize, null, null, null),
                "clEnqueueNDRangeKernel(FlatCache 2D prefill)");
    }

    private void enqueueGeneratedFinalOutputStage(GeneratedNoiseKernel generated,
                                                  SlabVmNoiseCellGridRequest request,
                                                  long permutationsBuffer,
                                                  long slotBuffer,
                                                  PointerBuffer globalWorkSize) {
        generated.assertOpen();
        int arg = 0;
        check(CL12.clSetKernelArg1p(generated.kernel, arg++, permutationsBuffer),
                "clSetKernelArg(generated final wave permutations)");
        check(CL12.clSetKernelArg1p(generated.kernel, arg++, slotBuffer),
                "clSetKernelArg(generated final wave external slots)");
        check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockX),
                "clSetKernelArg(generated final wave first x)");
        check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockY),
                "clSetKernelArg(generated final wave first y)");
        check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.firstBlockZ),
                "clSetKernelArg(generated final wave first z)");
        check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cellWidth),
                "clSetKernelArg(generated final wave cell width)");
        check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cellHeight),
                "clSetKernelArg(generated final wave cell height)");
        check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.cells),
                "clSetKernelArg(generated final wave cells)");
        check(CL12.clSetKernelArg1i(generated.kernel, arg++, request.layout),
                "clSetKernelArg(generated final wave layout)");
        check(CL12.clSetKernelArg1d(generated.kernel, arg++, request.hoistBase),
                "clSetKernelArg(generated final wave hoist)");
        check(CL12.clSetKernelArg1p(generated.kernel, arg++, slotBuffer),
                "clSetKernelArg(generated final wave output slots)");
        check(CL12.clSetKernelArg1i(generated.kernel, arg, request.n),
                "clSetKernelArg(generated final wave n)");
        check(CL12.clEnqueueNDRangeKernel(this.queue, generated.kernel, 1,
                null, globalWorkSize, null, null, null),
                "clEnqueueNDRangeKernel(generated final wave)");
    }

    private void enqueueSlabVmFinalOutputStage(FinalOutputStage stage,
                                               SlabVmNoiseCellGridRequest request,
                                               int slotBufferSlotCount,
                                               long slotBuffer,
                                               PointerBuffer globalWorkSize,
                                               IntBuffer err) {
        if (stage.bytecode() == null || stage.bytecode().length == 0) {
            throw new IllegalArgumentException("final output VM stage bytecode is empty");
        }
        if (stage.targetSlotBufferIndex() < 0 || stage.targetSlotBufferIndex() >= slotBufferSlotCount) {
            throw new IllegalArgumentException("final output VM target slot " + stage.targetSlotBufferIndex()
                    + " outside compact slot count " + slotBufferSlotCount);
        }
        double[] constants = stage.constants() == null ? new double[0] : stage.constants();
        ensureFinalOutputVmStageBuffers(stage, constants, err);

        int arg = 0;
        check(CL12.clSetKernelArg1p(this.slabVmCellGridSlotBufferKernel, arg++, stage.bytecodeBuffer()),
                "clSetKernelArg(final VM bytecode)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, stage.bytecode().length),
                "clSetKernelArg(final VM bytecode length)");
        check(CL12.clSetKernelArg1p(this.slabVmCellGridSlotBufferKernel, arg++, stage.constantsBuffer()),
                "clSetKernelArg(final VM constants)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, constants.length),
                "clSetKernelArg(final VM constant count)");
        check(CL12.clSetKernelArg1p(this.slabVmCellGridSlotBufferKernel, arg++, slotBuffer),
                "clSetKernelArg(final VM slots)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, slotBufferSlotCount),
                "clSetKernelArg(final VM slot count)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, request.n),
                "clSetKernelArg(final VM row stride)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, request.firstBlockX),
                "clSetKernelArg(final VM first x)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, request.firstBlockY),
                "clSetKernelArg(final VM first y)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, request.firstBlockZ),
                "clSetKernelArg(final VM first z)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, request.cellWidth),
                "clSetKernelArg(final VM cell width)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, request.cellHeight),
                "clSetKernelArg(final VM cell height)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, request.cells),
                "clSetKernelArg(final VM cells)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, request.layout),
                "clSetKernelArg(final VM layout)");
        check(CL12.clSetKernelArg1d(this.slabVmCellGridSlotBufferKernel, arg++, request.hoistBase),
                "clSetKernelArg(final VM hoist)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg++, stage.targetSlotBufferIndex()),
                "clSetKernelArg(final VM target slot)");
        check(CL12.clSetKernelArg1i(this.slabVmCellGridSlotBufferKernel, arg, request.n),
                "clSetKernelArg(final VM n)");
        check(CL12.clEnqueueNDRangeKernel(this.queue, this.slabVmCellGridSlotBufferKernel, 1,
                null, globalWorkSize, null, null, null),
                "clEnqueueNDRangeKernel(dfc_slab_vm_eval_cell_grid_slot_buffer)");
    }

    private void ensureFinalOutputVmStageBuffers(FinalOutputStage stage, double[] constants, IntBuffer err) {
        if (stage.vmBuffersUploaded()) {
            return;
        }
        ByteBuffer bytecodeHost = null;
        DoubleBuffer constantsHost = null;
        long bytecodeBuffer = 0L;
        long constantsBuffer = 0L;
        try {
            bytecodeHost = MemoryUtil.memAlloc(stage.bytecode().length);
            bytecodeHost.put(stage.bytecode()).flip();
            bytecodeBuffer = CL12.clCreateBuffer(this.context,
                    CL12.CL_MEM_READ_ONLY | CL12.CL_MEM_COPY_HOST_PTR, bytecodeHost, err);
            check(err.get(0), "clCreateBuffer(generated final output VM bytecode)");
            if (constants.length == 0) {
                constantsBuffer = CL12.clCreateBuffer(this.context, CL12.CL_MEM_READ_ONLY, 1L, err);
                check(err.get(0), "clCreateBuffer(generated final output VM empty constants)");
            } else {
                constantsHost = MemoryUtil.memAllocDouble(constants.length);
                constantsHost.put(constants).flip();
                constantsBuffer = CL12.clCreateBuffer(this.context,
                        CL12.CL_MEM_READ_ONLY | CL12.CL_MEM_COPY_HOST_PTR, constantsHost, err);
                check(err.get(0), "clCreateBuffer(generated final output VM constants)");
            }
            stage.setVmBuffers(bytecodeBuffer, constantsBuffer);
        } catch (RuntimeException exception) {
            releaseMem(bytecodeBuffer);
            releaseMem(constantsBuffer);
            throw exception;
        } finally {
            free(bytecodeHost);
            free(constantsHost);
        }
    }

    private void enqueueGeneratedFinalOutputKernel(GeneratedNoiseKernel finalKernel,
                                                   SlabVmNoiseCellGridRequest request,
                                                   long permutationsBuffer,
                                                   long slotBuffer,
                                                   long outBuffer,
                                                   PointerBuffer globalWorkSize) {
        int arg = 0;
        check(CL12.clSetKernelArg1p(finalKernel.kernel, arg++, permutationsBuffer),
                "clSetKernelArg(generated final output permutations)");
        check(CL12.clSetKernelArg1p(finalKernel.kernel, arg++, slotBuffer),
                "clSetKernelArg(generated final output external slots)");
        check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.firstBlockX),
                "clSetKernelArg(generated final output first x)");
        check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.firstBlockY),
                "clSetKernelArg(generated final output first y)");
        check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.firstBlockZ),
                "clSetKernelArg(generated final output first z)");
        check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.cellWidth),
                "clSetKernelArg(generated final output cell width)");
        check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.cellHeight),
                "clSetKernelArg(generated final output cell height)");
        check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.cells),
                "clSetKernelArg(generated final output cells)");
        check(CL12.clSetKernelArg1i(finalKernel.kernel, arg++, request.layout),
                "clSetKernelArg(generated final output layout)");
        check(CL12.clSetKernelArg1d(finalKernel.kernel, arg++, request.hoistBase),
                "clSetKernelArg(generated final output hoist)");
        check(CL12.clSetKernelArg1p(finalKernel.kernel, arg++, outBuffer),
                "clSetKernelArg(generated final output output)");
        check(CL12.clSetKernelArg1i(finalKernel.kernel, arg, request.n),
                "clSetKernelArg(generated final output n)");
        check(CL12.clEnqueueNDRangeKernel(this.queue, finalKernel.kernel, 1,
                null, globalWorkSize, null, null, null),
                "clEnqueueNDRangeKernel(generated final output)");
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
        for (GeneratedNoiseKernel kernel : this.generatedKernelCache.values()) {
            kernel.close();
        }
        this.generatedKernelCache.clear();
        releaseDeviceBuffers();
        releaseKernel(this.slabVmDirectNoiseKernel);
        releaseKernel(this.slabVmDirectDemoKernel);
        releaseKernel(this.slabVmFillNoiseSlotsBySlotKernel);
        releaseKernel(this.slabVmFillNoiseSlotsKernel);
        releaseKernel(this.slabVmFillDemoSlotsKernel);
        releaseKernel(this.slabVmCellGridSlotBufferKernel);
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

    private static void validateGeneratedCellGridRequest(SlabVmGeneratedCellGridRequest request) {
        if (request.bytecode == null || request.bytecode.length == 0) {
            throw new IllegalArgumentException("bytecode is empty");
        }
        if (request.constants == null) {
            throw new IllegalArgumentException("constants is null");
        }
        if (request.out == null) {
            throw new IllegalArgumentException("out is null");
        }
        if (request.slotCount != 2) {
            throw new IllegalArgumentException("generated demo slots require exactly 2 slots");
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
    }

    private static void validateNoiseCellGridRequest(SlabVmNoiseCellGridRequest request) {
        if (request.bytecode == null || request.bytecode.length == 0) {
            throw new IllegalArgumentException("bytecode is empty");
        }
        if (request.constants == null
                || request.permutations == null
                || request.origins == null
                || request.inputFactors == null
                || request.ampFactors == null
                || request.branchOctaveOffsets == null
                || request.branchOctaveCounts == null
                || request.branchCoordScales == null
                || request.slotValueFactors == null) {
            throw new IllegalArgumentException("noise request arrays must be non-null");
        }
        if (request.out == null) {
            throw new IllegalArgumentException("out is null");
        }
        if (request.slotCount <= 0 || request.branchesPerSlot <= 0 || request.octavesPerBranch <= 0) {
            throw new IllegalArgumentException("invalid noise slot layout");
        }
        if (request.cellWidth <= 0 || request.cellHeight <= 0 || request.cells <= 0) {
            throw new IllegalArgumentException("invalid cell grid dimensions");
        }
        if (request.layout != DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ
                && request.layout != DfcOpenClRuntime.CELL_GRID_LAYOUT_Y_COLUMN) {
            throw new IllegalArgumentException("unknown cell grid layout " + request.layout);
        }
        long expectedN = (long) request.cellWidth * request.cellWidth * request.cellHeight * request.cells;
        if (expectedN > Integer.MAX_VALUE || request.n != (int) expectedN) {
            throw new IllegalArgumentException("cell grid element count does not match n");
        }
        if (request.out.length < request.n) {
            throw new IllegalArgumentException("invalid output length");
        }
        long branchCount = (long) request.slotCount * request.branchesPerSlot;
        if (branchCount > Integer.MAX_VALUE
                || request.branchOctaveOffsets.length < branchCount
                || request.branchOctaveCounts.length < branchCount) {
            throw new IllegalArgumentException("noise branch octave metadata is too short");
        }
        long octaveCount = 0L;
        for (int branch = 0; branch < branchCount; branch++) {
            int offset = request.branchOctaveOffsets[branch];
            int count = request.branchOctaveCounts[branch];
            if (offset < 0 || count < 0) {
                throw new IllegalArgumentException("noise branch octave metadata contains negative values");
            }
            octaveCount = Math.max(octaveCount, (long) offset + count);
        }
        if (octaveCount > Integer.MAX_VALUE
                || request.permutations.length < octaveCount * DfcOpenClNoiseDescriptor.PERMUTATION_STRIDE) {
            throw new IllegalArgumentException("noise permutations are too short");
        }
        if (request.origins.length < octaveCount * 3L
                || request.inputFactors.length < octaveCount
                || request.ampFactors.length < octaveCount
                || request.branchCoordScales.length < branchCount
                || request.slotValueFactors.length < request.slotCount) {
            throw new IllegalArgumentException("noise descriptor arrays are too short");
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

    private boolean gridInputsReady(SlabVmCellGridRequest request) {
        return bufferReady(this.gridBytecodeBuffer, request.bytecode.length, CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.gridConstantsBuffer, doubleBytes(request.constants.length), CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.gridSlotsBuffer, doubleBytes(request.slotRowsFlat.length), CL12.CL_MEM_READ_ONLY);
    }

    private boolean noiseInputsReady(SlabVmNoiseCellGridRequest request) {
        return bufferReady(this.gridBytecodeBuffer, request.bytecode.length, CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.gridConstantsBuffer, doubleBytes(request.constants.length), CL12.CL_MEM_READ_ONLY)
                && noiseDescriptorInputsReady(request);
    }

    private boolean noiseDescriptorInputsReady(SlabVmNoiseCellGridRequest request) {
        return bufferReady(this.noisePermutationsBuffer, request.permutations.length, CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.noiseOriginsBuffer, doubleBytes(request.origins.length), CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.noiseInputFactorsBuffer, doubleBytes(request.inputFactors.length),
                CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.noiseAmpFactorsBuffer, doubleBytes(request.ampFactors.length),
                CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.noiseBranchOctaveOffsetsBuffer, intBytes(request.branchOctaveOffsets.length),
                CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.noiseBranchOctaveCountsBuffer, intBytes(request.branchOctaveCounts.length),
                CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.noiseBranchScalesBuffer, doubleBytes(request.branchCoordScales.length),
                CL12.CL_MEM_READ_ONLY)
                && bufferReady(this.noiseSlotFactorsBuffer, doubleBytes(request.slotValueFactors.length),
                CL12.CL_MEM_READ_ONLY);
    }

    private static boolean bufferReady(DeviceBuffer buffer, long bytes, long flags) {
        long requestedBytes = Math.max(1L, bytes);
        return buffer.mem != 0L && buffer.bytes >= requestedBytes && buffer.flags == flags;
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
        if (!DfcOpenClConfig.directStagingEnabled()) {
            check(CL12.clEnqueueWriteBuffer(this.queue, buffer, true, 0L, values, null, null), op);
            return;
        }
        DoubleBuffer host = copyToHostDoubleBuffer(this.doubleStagingBuffer, values);
        check(CL12.clEnqueueWriteBuffer(this.queue, buffer, true, 0L, host, null, null), op);
    }

    private void writeIntArray(long buffer, int[] values, String op) {
        if (values.length == 0) {
            return;
        }
        check(CL12.clEnqueueWriteBuffer(this.queue, buffer, true, 0L, values, null, null), op);
    }

    private static long doubleBytes(int elements) {
        return (long) Math.max(1, elements) * Double.BYTES;
    }

    private static long intBytes(int elements) {
        return (long) Math.max(1, elements) * Integer.BYTES;
    }

    private static DoubleBuffer ensureHostDoubleBuffer(HostDoubleBuffer buffer, int elements) {
        return buffer.ensure(elements);
    }

    private static DoubleBuffer copyToHostDoubleBuffer(HostDoubleBuffer buffer, double[] values) {
        DoubleBuffer host = buffer.ensure(values.length);
        host.clear();
        host.put(values);
        host.flip();
        return host;
    }

    private void releaseDeviceBuffers() {
        this.gridOutHostBuffer.release();
        this.doubleStagingBuffer.release();
        this.flatCache2dTableFirstNoiseZBuffer.release();
        this.flatCache2dTableFirstNoiseXBuffer.release();
        this.flatCache2dTableSidesBuffer.release();
        this.flatCache2dTableOffsetsBuffer.release();
        this.flatCache2dSlotTableIndicesBuffer.release();
        this.flatCache2dSlotCompactIndicesBuffer.release();
        this.flatCache2dValuesBuffer.release();
        this.generatedSlotBuffer.release();
        this.noiseSlotFactorsBuffer.release();
        this.noiseBranchScalesBuffer.release();
        this.noiseBranchOctaveCountsBuffer.release();
        this.noiseBranchOctaveOffsetsBuffer.release();
        this.noiseAmpFactorsBuffer.release();
        this.noiseInputFactorsBuffer.release();
        this.noiseOriginsBuffer.release();
        this.noisePermutationsBuffer.release();
        this.generatedExternalSlotsBuffer.release();
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

    private static void free(IntBuffer buffer) {
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

    record SlabVmGeneratedCellGridRequest(
            byte[] bytecode,
            double[] constants,
            int slotCount,
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

    record SlabVmNoiseCellGridRequest(
            byte[] bytecode,
            double[] constants,
            byte[] permutations,
            double[] origins,
            double[] inputFactors,
            double[] ampFactors,
            int[] branchOctaveOffsets,
            int[] branchOctaveCounts,
            double[] branchCoordScales,
            double[] slotValueFactors,
            int slotCount,
            int branchesPerSlot,
            int octavesPerBranch,
            int firstBlockX,
            int firstBlockY,
            int firstBlockZ,
            int cellWidth,
            int cellHeight,
            int cells,
            int layout,
            double hoistBase,
            double[] out,
            int n) {
    }

    record FlatCache2dPrefill(double[] flatValues,
                              int[] slotCompactIndices,
                              int[] slotTableIndices,
                              int[] tableOffsets,
                              int[] tableSides,
                              int[] tableFirstNoiseX,
                              int[] tableFirstNoiseZ,
                              int slotCount) {
    }

    record SlabVmResult(long elapsedNanos) {
    }

    record FinalOutputTraceResult(
            long elapsedNanos,
            long inputWriteNanos,
            long initialSlotWriteNanos,
            long[] stageNanos,
            long[] stageSubmitNanos,
            long[] stageWaitNanos,
            long finalKernelNanos,
            long finalSubmitNanos,
            long finalWaitNanos,
            long readbackNanos) {
    }

    static final class FinalOutputStage implements AutoCloseable {
        private final GeneratedNoiseKernel generatedKernel;
        private final byte[] bytecode;
        private final double[] constants;
        private final int targetSlotBufferIndex;
        private long bytecodeBuffer;
        private long constantsBuffer;
        private boolean closed;

        private FinalOutputStage(GeneratedNoiseKernel generatedKernel,
                                 byte[] bytecode,
                                 double[] constants,
                                 int targetSlotBufferIndex) {
            this.generatedKernel = generatedKernel;
            this.bytecode = bytecode;
            this.constants = constants;
            this.targetSlotBufferIndex = targetSlotBufferIndex;
        }

        static FinalOutputStage generated(GeneratedNoiseKernel kernel) {
            return new FinalOutputStage(kernel, null, null, -1);
        }

        static FinalOutputStage slabVmSlot(byte[] bytecode, double[] constants, int targetSlotBufferIndex) {
            return new FinalOutputStage(null, bytecode, constants, targetSlotBufferIndex);
        }

        GeneratedNoiseKernel generatedKernel() {
            return this.generatedKernel;
        }

        byte[] bytecode() {
            return this.bytecode;
        }

        double[] constants() {
            return this.constants;
        }

        int targetSlotBufferIndex() {
            return this.targetSlotBufferIndex;
        }

        boolean generated() {
            return this.generatedKernel != null;
        }

        boolean vmBuffersUploaded() {
            return this.bytecodeBuffer != 0L && this.constantsBuffer != 0L;
        }

        long vmUploadBytes() {
            int bytecodeBytes = this.bytecode == null ? 0 : this.bytecode.length;
            int constantCount = this.constants == null ? 0 : this.constants.length;
            return bytecodeBytes + (long) constantCount * Double.BYTES;
        }

        long bytecodeBuffer() {
            return this.bytecodeBuffer;
        }

        long constantsBuffer() {
            return this.constantsBuffer;
        }

        void setVmBuffers(long bytecodeBuffer, long constantsBuffer) {
            this.bytecodeBuffer = bytecodeBuffer;
            this.constantsBuffer = constantsBuffer;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            releaseMem(this.bytecodeBuffer);
            releaseMem(this.constantsBuffer);
            this.bytecodeBuffer = 0L;
            this.constantsBuffer = 0L;
        }
    }

    static final class GeneratedNoiseKernel implements AutoCloseable {
        private final long program;
        private final long kernel;
        private final String buildLog;
        private boolean closed;

        private GeneratedNoiseKernel(long program, long kernel, String buildLog) {
            this.program = program;
            this.kernel = kernel;
            this.buildLog = buildLog;
        }

        String buildLog() {
            return this.buildLog;
        }

        private void assertOpen() {
            if (this.closed) {
                throw new IllegalStateException("generated OpenCL kernel is closed");
            }
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            releaseKernel(this.kernel);
            releaseProgram(this.program);
        }
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

    private static final class HostDoubleBuffer {
        DoubleBuffer buffer;

        DoubleBuffer ensure(int elements) {
            int requested = Math.max(1, elements);
            if (this.buffer != null && this.buffer.capacity() >= requested) {
                return this.buffer;
            }
            release();
            this.buffer = MemoryUtil.memAllocDouble(requested);
            return this.buffer;
        }

        void release() {
            if (this.buffer != null) {
                MemoryUtil.memFree(this.buffer);
                this.buffer = null;
            }
        }
    }
}
