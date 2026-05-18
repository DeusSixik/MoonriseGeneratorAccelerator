package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.List;

final class DfcOpenClBuildProbe {
    private static final int SMOKE_SAMPLE_COUNT = 8;
    private static final int SLAB_SMOKE_CELL_WIDTH = 4;
    private static final int SLAB_SMOKE_COUNT = SLAB_SMOKE_CELL_WIDTH * SLAB_SMOKE_CELL_WIDTH;
    private static final double SMOKE_EPSILON = 1.0E-9;

    private DfcOpenClBuildProbe() {
    }

    static Result compileFirstWorking(List<DfcOpenClDeviceEnumerator.Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Result.failed(null, "No OpenCL candidates to compile.");
        }

        String source = DfcOpenClSources.smokeProbeSource();
        String lastError = null;
        for (DfcOpenClDeviceEnumerator.Candidate candidate : candidates) {
            try {
                String buildLog = compile(candidate, source);
                return Result.passed(candidate, buildLog);
            } catch (Throwable throwable) {
                lastError = candidate.info().shortDescription() + ": " + errorMessage(throwable);
            }
        }
        return Result.failed(null, lastError);
    }

    private static String compile(DfcOpenClDeviceEnumerator.Candidate candidate, String source) {
        ByteBuffer sourceBuffer = null;
        long context = 0L;
        long program = 0L;
        long queue = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            PointerBuffer properties = stack.callocPointer(3);
            properties.put(CL12.CL_CONTEXT_PLATFORM).put(candidate.platform()).rewind();
            context = CL12.clCreateContext(properties, candidate.device(), null, 0L, err);
            check(err.get(0), "clCreateContext");

            sourceBuffer = MemoryUtil.memUTF8(source, true);
            PointerBuffer sources = stack.callocPointer(1);
            sources.put(0, sourceBuffer);
            program = CL12.clCreateProgramWithSource(context, sources, null, err);
            check(err.get(0), "clCreateProgramWithSource");

            int buildError = CL12.clBuildProgram(program, candidate.device(), "-cl-std=CL1.2", null, 0L);
            String buildLog = getBuildLog(program, candidate.device());
            int buildStatus = getBuildStatus(program, candidate.device());
            if (buildStatus != CL12.CL_BUILD_SUCCESS) {
                throw new IllegalStateException("OpenCL smoke build failed: " + trimBuildLog(buildLog)
                        + " (error " + buildError + ")");
            }
            check(buildError, "clBuildProgram");

            queue = CL12.clCreateCommandQueue(context, candidate.device(), 0L, err);
            check(err.get(0), "clCreateCommandQueue");
            runMathSmoke(context, queue, program);
            runSlabVmSmoke(context, queue, program);
            return trimBuildLog(buildLog);
        } finally {
            if (queue != 0L) {
                CL12.clReleaseCommandQueue(queue);
            }
            if (program != 0L) {
                CL12.clReleaseProgram(program);
            }
            if (context != 0L) {
                CL12.clReleaseContext(context);
            }
            if (sourceBuffer != null) {
                MemoryUtil.memFree(sourceBuffer);
            }
        }
    }

    private static void runMathSmoke(long context, long queue, long program) {
        long kernel = 0L;
        long outBuffer = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            kernel = CL12.clCreateKernel(program, "dfc_probe_math", err);
            check(err.get(0), "clCreateKernel(dfc_probe_math)");
            outBuffer = CL12.clCreateBuffer(context, CL12.CL_MEM_WRITE_ONLY,
                    (long) SMOKE_SAMPLE_COUNT * Double.BYTES, err);
            check(err.get(0), "clCreateBuffer(probe output)");
            check(CL12.clSetKernelArg1p(kernel, 0, outBuffer), "clSetKernelArg(output)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, SMOKE_SAMPLE_COUNT);
            check(CL12.clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(dfc_probe_math)");

            DoubleBuffer out = stack.callocDouble(SMOKE_SAMPLE_COUNT);
            check(CL12.clEnqueueReadBuffer(queue, outBuffer, true, 0L, out, null, null),
                    "clEnqueueReadBuffer(probe output)");
            check(CL12.clFinish(queue), "clFinish(math probe)");
            validateMathSmokeOutput(out);
        } finally {
            if (outBuffer != 0L) {
                CL12.clReleaseMemObject(outBuffer);
            }
            if (kernel != 0L) {
                CL12.clReleaseKernel(kernel);
            }
        }
    }

    private static void validateMathSmokeOutput(DoubleBuffer out) {
        for (int i = 0; i < SMOKE_SAMPLE_COUNT; i++) {
            double x = i * 0.25D - 1.0D;
            double clamped = x < -1.0D ? -1.0D : (x > 1.0D ? 1.0D : x);
            double expected = clamped / 2.0D - clamped * clamped * clamped / 24.0D
                    + ((x - -1.0D) / (1.0D - -1.0D));
            double actual = out.get(i);
            if (!Double.isFinite(actual) || Math.abs(actual - expected) > SMOKE_EPSILON) {
                throw new IllegalStateException("OpenCL smoke kernel mismatch at " + i
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private static void runSlabVmSmoke(long context, long queue, long program) {
        long kernel = 0L;
        long bytecodeBuffer = 0L;
        long constantsBuffer = 0L;
        long slotsBuffer = 0L;
        long outBuffer = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.callocInt(1);
            kernel = CL12.clCreateKernel(program, "dfc_slab_vm_eval", err);
            check(err.get(0), "clCreateKernel(dfc_slab_vm_eval)");

            ByteBuffer bytecode = stack.bytes(
                    (byte) 2, (byte) 0,
                    (byte) 2, (byte) 1,
                    (byte) 32,
                    (byte) 19,
                    (byte) 32,
                    (byte) 16,
                    (byte) 32,
                    (byte) 18,
                    (byte) 33,
                    (byte) 17,
                    (byte) 1, (byte) 0, (byte) 0,
                    (byte) 34,
                    (byte) 51,
                    (byte) 32);
            DoubleBuffer constants = stack.doubles(0.1D);
            DoubleBuffer slots = stack.mallocDouble(SLAB_SMOKE_COUNT * 2);
            for (int i = 0; i < SLAB_SMOKE_COUNT; i++) {
                slots.put(i, i * 0.5D);
                slots.put(SLAB_SMOKE_COUNT + i, 10.0D - i * 0.25D);
            }

            bytecodeBuffer = CL12.clCreateBuffer(context, CL12.CL_MEM_READ_ONLY | CL12.CL_MEM_COPY_HOST_PTR,
                    bytecode, err);
            check(err.get(0), "clCreateBuffer(slab bytecode)");
            constantsBuffer = CL12.clCreateBuffer(context, CL12.CL_MEM_READ_ONLY | CL12.CL_MEM_COPY_HOST_PTR,
                    constants, err);
            check(err.get(0), "clCreateBuffer(slab constants)");
            slotsBuffer = CL12.clCreateBuffer(context, CL12.CL_MEM_READ_ONLY | CL12.CL_MEM_COPY_HOST_PTR,
                    slots, err);
            check(err.get(0), "clCreateBuffer(slab slots)");
            outBuffer = CL12.clCreateBuffer(context, CL12.CL_MEM_WRITE_ONLY,
                    (long) SLAB_SMOKE_COUNT * Double.BYTES, err);
            check(err.get(0), "clCreateBuffer(slab output)");

            int arg = 0;
            check(CL12.clSetKernelArg1p(kernel, arg++, bytecodeBuffer), "clSetKernelArg(slab bytecode)");
            check(CL12.clSetKernelArg1i(kernel, arg++, bytecode.remaining()), "clSetKernelArg(slab bytecode length)");
            check(CL12.clSetKernelArg1p(kernel, arg++, constantsBuffer), "clSetKernelArg(slab constants)");
            check(CL12.clSetKernelArg1i(kernel, arg++, constants.remaining()), "clSetKernelArg(slab constant count)");
            check(CL12.clSetKernelArg1p(kernel, arg++, slotsBuffer), "clSetKernelArg(slab slots)");
            check(CL12.clSetKernelArg1i(kernel, arg++, 2), "clSetKernelArg(slab slot count)");
            check(CL12.clSetKernelArg1i(kernel, arg++, SLAB_SMOKE_COUNT), "clSetKernelArg(slab row stride)");
            check(CL12.clSetKernelArg1i(kernel, arg++, 100), "clSetKernelArg(slab start x)");
            check(CL12.clSetKernelArg1i(kernel, arg++, 200), "clSetKernelArg(slab start z)");
            check(CL12.clSetKernelArg1i(kernel, arg++, 64), "clSetKernelArg(slab block y)");
            check(CL12.clSetKernelArg1i(kernel, arg++, SLAB_SMOKE_CELL_WIDTH), "clSetKernelArg(slab cell width)");
            check(CL12.clSetKernelArg1i(kernel, arg++, 0), "clSetKernelArg(slab layout)");
            check(CL12.clSetKernelArg1i(kernel, arg++, 0), "clSetKernelArg(slab col xi)");
            check(CL12.clSetKernelArg1i(kernel, arg++, 0), "clSetKernelArg(slab col zi)");
            check(CL12.clSetKernelArg1i(kernel, arg++, 0), "clSetKernelArg(slab cell height)");
            check(CL12.clSetKernelArg1d(kernel, arg++, 3.25D), "clSetKernelArg(slab hoist)");
            check(CL12.clSetKernelArg1p(kernel, arg++, outBuffer), "clSetKernelArg(slab output)");
            check(CL12.clSetKernelArg1i(kernel, arg, SLAB_SMOKE_COUNT), "clSetKernelArg(slab n)");

            PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(0, SLAB_SMOKE_COUNT);
            check(CL12.clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, null, null, null),
                    "clEnqueueNDRangeKernel(dfc_slab_vm_eval)");

            DoubleBuffer out = stack.callocDouble(SLAB_SMOKE_COUNT);
            check(CL12.clEnqueueReadBuffer(queue, outBuffer, true, 0L, out, null, null),
                    "clEnqueueReadBuffer(slab output)");
            check(CL12.clFinish(queue), "clFinish(slab probe)");
            validateSlabVmSmokeOutput(out);
        } finally {
            if (outBuffer != 0L) {
                CL12.clReleaseMemObject(outBuffer);
            }
            if (slotsBuffer != 0L) {
                CL12.clReleaseMemObject(slotsBuffer);
            }
            if (constantsBuffer != 0L) {
                CL12.clReleaseMemObject(constantsBuffer);
            }
            if (bytecodeBuffer != 0L) {
                CL12.clReleaseMemObject(bytecodeBuffer);
            }
            if (kernel != 0L) {
                CL12.clReleaseKernel(kernel);
            }
        }
    }

    private static void validateSlabVmSmokeOutput(DoubleBuffer out) {
        double squeeze = 1.0D / 2.0D - 1.0D / 24.0D;
        for (int i = 0; i < SLAB_SMOKE_COUNT; i++) {
            int ix = i / SLAB_SMOKE_CELL_WIDTH;
            int iz = i - ix * SLAB_SMOKE_CELL_WIDTH;
            double expected = i * 0.5D
                    + (10.0D - i * 0.25D)
                    + 3.25D
                    + (100 + ix)
                    - (200 + iz)
                    + squeeze;
            double actual = out.get(i);
            if (!Double.isFinite(actual) || Math.abs(actual - expected) > SMOKE_EPSILON) {
                throw new IllegalStateException("OpenCL slab VM smoke mismatch at " + i
                        + ": expected=" + expected + ", actual=" + actual);
            }
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

    private static String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static void check(int error, String op) {
        if (error != CL12.CL_SUCCESS) {
            throw new IllegalStateException(op + " failed with OpenCL error " + error);
        }
    }

    record Result(boolean tested, boolean passed, DfcOpenClDeviceEnumerator.Candidate candidate,
                  DfcOpenClDeviceInfo device, String buildLog, String error) {
        static Result skipped() {
            return new Result(false, false, null, null, null, null);
        }

        private static Result passed(DfcOpenClDeviceEnumerator.Candidate candidate, String buildLog) {
            return new Result(true, true, candidate, candidate.info(), buildLog, null);
        }

        private static Result failed(DfcOpenClDeviceInfo device, String error) {
            return new Result(true, false, null, device, null, error);
        }
    }
}
