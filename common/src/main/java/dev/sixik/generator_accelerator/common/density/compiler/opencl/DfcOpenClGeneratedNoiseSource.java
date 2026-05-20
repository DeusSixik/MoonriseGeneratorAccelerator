package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import java.util.LinkedHashMap;
import java.util.Map;

final class DfcOpenClGeneratedNoiseSource {
    static final String KERNEL_NAME = "dfc_generated_real_noise";
    private static final int OP_PUSH_CONST = 1;
    private static final int OP_PUSH_SLOT = 2;
    private static final int OP_COND_NEG_SCALE = 3;
    private static final int OP_Y_CLAMPED_GRADIENT = 4;
    private static final int OP_RANGE_CHOICE = 5;
    private static final int OP_BLOCK_X = 16;
    private static final int OP_BLOCK_Y = 17;
    private static final int OP_BLOCK_Z = 18;
    private static final int OP_HOIST = 19;
    private static final int OP_ADD = 32;
    private static final int OP_SUB = 33;
    private static final int OP_MUL = 34;
    private static final int OP_DIV = 35;
    private static final int OP_MIN = 36;
    private static final int OP_MAX = 37;
    private static final int OP_NEG = 48;
    private static final int OP_ABS = 49;
    private static final int OP_SQUARE = 50;
    private static final int OP_SQUEEZE = 51;

    private DfcOpenClGeneratedNoiseSource() {
    }

    enum WrapMode {
        WRAP,
        NOWRAP
    }

    private enum ExternalSlotLayout {
        ROW_MAJOR,
        SLOT_MAJOR
    }

    record BuildResult(String source, int coordScaleTemps, int coordScaleRefs) {
    }

    static BuildResult build(DfcOpenClNoiseDescriptor descriptor, int usedSlotCount) {
        return build(descriptor, usedSlotCount, WrapMode.WRAP);
    }

    static BuildResult build(DfcOpenClNoiseDescriptor descriptor, int usedSlotCount, boolean wrapAxis) {
        return build(descriptor, usedSlotCount, wrapAxis ? WrapMode.WRAP : WrapMode.NOWRAP);
    }

    static BuildResult build(DfcOpenClNoiseDescriptor descriptor, int usedSlotCount, WrapMode wrapMode) {
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        Map<Long, ScaleUse> scaleUses = collectScaleUses(descriptor, safeUsedSlots);
        int scaleTempCount = 0;
        int scaleRefCount = 0;
        for (ScaleUse use : scaleUses.values()) {
            if (use.count > 1) {
                use.tempIndex = scaleTempCount++;
                scaleRefCount += use.count;
            }
        }
        StringBuilder source = new StringBuilder(8192 + descriptor.totalOctaves * 512);
        source.append('\n')
                .append("__kernel void ").append(KERNEL_NAME)
                .append("(DFC_NOISE_MEM const uchar *permutations, ")
                .append("__global const double *external_slots, ")
                .append("int first_block_x, int first_block_y, int first_block_z, ")
                .append("int cell_w, int cell_h, int cells, double hoist_base, ")
                .append("__global double *out, int n) {\n")
                .append("    int gid = (int) get_global_id(0);\n")
                .append("    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) return;\n")
                .append("    double bx;\n")
                .append("    double by;\n")
                .append("    double bz;\n")
                .append("    int cell;\n")
                .append("    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z, ")
                .append("cell_w, cell_h, cells, &bx, &by, &bz, &cell)) return;\n")
                .append("    double value = 0.0;\n");
        appendBody(source, descriptor, safeUsedSlots, scaleUses, wrapMode == WrapMode.WRAP, "    ");
        source.append("    double y_hoist = hoist_base + (double) (cell & 7) * 0.03125;\n")
                .append("    out[gid] = value + y_hoist + bx - bz + dfc_squeeze(by * 0.1);\n")
                .append("}\n");
        return new BuildResult(source.toString(), scaleTempCount, scaleRefCount);
    }

    static BuildResult buildCompiledPlan(DfcOpenClNoiseDescriptor descriptor,
                                          int usedSlotCount,
                                          byte[] slabProgram,
                                          double[] slabConstants,
                                          String hoistExpression,
                                          String[] slotCoordXExpressions,
                                          String[] slotCoordYExpressions,
                                          String[] slotCoordZExpressions,
                                          boolean[] externalSlots,
                                          DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                          WrapMode wrapMode) {
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        boolean customCoords = hasSlotCoords(safeUsedSlots, slotCoordXExpressions, slotCoordYExpressions,
                slotCoordZExpressions);
        Map<Long, ScaleUse> scaleUses = customCoords ? Map.of() : collectScaleUses(descriptor, safeUsedSlots);
        int scaleTempCount = 0;
        int scaleRefCount = 0;
        for (ScaleUse use : scaleUses.values()) {
            if (use.count > 1) {
                use.tempIndex = scaleTempCount++;
                scaleRefCount += use.count;
            }
        }
        StringBuilder source = new StringBuilder(8192 + descriptor.totalOctaves * 512 + slabProgram.length * 96);
        source.append('\n')
                .append("__kernel void ").append(KERNEL_NAME)
                .append("(DFC_NOISE_MEM const uchar *permutations, ")
                .append("__global const double *external_slots, ")
                .append("int first_block_x, int first_block_y, int first_block_z, ")
                .append("int cell_w, int cell_h, int cells, double hoist_base, ")
                .append("__global double *out, int n) {\n")
                .append("    int gid = (int) get_global_id(0);\n")
                .append("    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) return;\n")
                .append("    double bx;\n")
                .append("    double by;\n")
                .append("    double bz;\n")
                .append("    int cell;\n")
                .append("    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z, ")
                .append("cell_w, cell_h, cells, &bx, &by, &bz, &cell)) return;\n");
        appendSlotLocals(source, descriptor, safeUsedSlots, scaleUses, wrapMode == WrapMode.WRAP, "    ",
                customCoords ? slotCoordXExpressions : null,
                customCoords ? slotCoordYExpressions : null,
                customCoords ? slotCoordZExpressions : null,
                externalSlots,
                computedSlots);
        source.append("    double y_hoist = ").append(hoistExpression == null ? "0.0" : hoistExpression).append(";\n");
        appendSlabProgramToOutput(source, slabProgram, slabConstants, safeUsedSlots, "y_hoist");
        source.append("}\n");
        return new BuildResult(source.toString(), scaleTempCount, scaleRefCount);
    }

    static BuildResult buildCompiledPlanChunk(DfcOpenClNoiseDescriptor descriptor,
                                              int startSlot,
                                              int endSlot,
                                              String[] slotCoordXExpressions,
                                              String[] slotCoordYExpressions,
                                              String[] slotCoordZExpressions,
                                              boolean[] externalSlots,
                                              DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                              WrapMode wrapMode) {
        int safeStartSlot = Math.max(0, startSlot);
        int safeEndSlot = Math.min(Math.max(safeStartSlot, endSlot), descriptor.slotCount - 1);
        int safeUsedSlots = descriptor.slotCount;
        boolean customCoords = hasSlotCoords(safeUsedSlots, slotCoordXExpressions, slotCoordYExpressions,
                slotCoordZExpressions);
        Map<Long, ScaleUse> scaleUses = customCoords ? Map.of() : collectScaleUses(descriptor, safeUsedSlots);
        int scaleTempCount = 0;
        int scaleRefCount = 0;
        for (ScaleUse use : scaleUses.values()) {
            if (use.count > 1) {
                use.tempIndex = scaleTempCount++;
                scaleRefCount += use.count;
            }
        }
        StringBuilder source = new StringBuilder(8192 + descriptor.totalOctaves * 512);
        source.append('\n')
                .append("__kernel void ").append(KERNEL_NAME)
                .append("(DFC_NOISE_MEM const uchar *permutations, ")
                .append("__global const double *external_slots, ")
                .append("int first_block_x, int first_block_y, int first_block_z, ")
                .append("int cell_w, int cell_h, int cells, double hoist_base, ")
                .append("__global double *out, int n) {\n")
                .append("    int gid = (int) get_global_id(0);\n")
                .append("    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) return;\n")
                .append("    double bx;\n")
                .append("    double by;\n")
                .append("    double bz;\n")
                .append("    int cell;\n")
                .append("    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z, ")
                .append("cell_w, cell_h, cells, &bx, &by, &bz, &cell)) return;\n");
        appendSlotLocals(source, descriptor, safeStartSlot, safeEndSlot, safeUsedSlots, scaleUses,
                wrapMode == WrapMode.WRAP, "    ",
                customCoords ? slotCoordXExpressions : null,
                customCoords ? slotCoordYExpressions : null,
                customCoords ? slotCoordZExpressions : null,
                externalSlots,
                computedSlots,
                ExternalSlotLayout.ROW_MAJOR,
                null);
        source.append("    double chunk_value = 0.0;\n");
        for (int slot = safeStartSlot; slot <= safeEndSlot; slot++) {
            source.append("    chunk_value += slot").append(slot).append(";\n");
        }
        source.append("    out[gid] = chunk_value;\n")
                .append("}\n");
        return new BuildResult(source.toString(), scaleTempCount, scaleRefCount);
    }

    static BuildResult buildCompiledPlanChunkSlotBuffer(DfcOpenClNoiseDescriptor descriptor,
                                                        int startSlot,
                                                        int endSlot,
                                                        String[] slotCoordXExpressions,
                                                        String[] slotCoordYExpressions,
                                                        String[] slotCoordZExpressions,
                                                        boolean[] externalSlots,
                                                        DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                                        WrapMode wrapMode) {
        int safeStartSlot = Math.max(0, startSlot);
        int safeEndSlot = Math.min(Math.max(safeStartSlot, endSlot), descriptor.slotCount - 1);
        int safeUsedSlots = descriptor.slotCount;
        boolean customCoords = hasSlotCoords(safeUsedSlots, slotCoordXExpressions, slotCoordYExpressions,
                slotCoordZExpressions);
        Map<Long, ScaleUse> scaleUses = customCoords ? Map.of() : collectScaleUses(descriptor, safeUsedSlots);
        int scaleTempCount = 0;
        int scaleRefCount = 0;
        for (ScaleUse use : scaleUses.values()) {
            if (use.count > 1) {
                use.tempIndex = scaleTempCount++;
                scaleRefCount += use.count;
            }
        }
        StringBuilder source = new StringBuilder(8192 + descriptor.totalOctaves * 512);
        source.append('\n')
                .append("__kernel void ").append(KERNEL_NAME)
                .append("(DFC_NOISE_MEM const uchar *permutations, ")
                .append("__global const double *external_slots, ")
                .append("int first_block_x, int first_block_y, int first_block_z, ")
                .append("int cell_w, int cell_h, int cells, double hoist_base, ")
                .append("__global double *out, int n) {\n")
                .append("    int gid = (int) get_global_id(0);\n")
                .append("    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) return;\n")
                .append("    double bx;\n")
                .append("    double by;\n")
                .append("    double bz;\n")
                .append("    int cell;\n")
                .append("    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z, ")
                .append("cell_w, cell_h, cells, &bx, &by, &bz, &cell)) return;\n");
        appendSlotLocals(source, descriptor, safeStartSlot, safeEndSlot, safeUsedSlots, scaleUses,
                wrapMode == WrapMode.WRAP, "    ",
                customCoords ? slotCoordXExpressions : null,
                customCoords ? slotCoordYExpressions : null,
                customCoords ? slotCoordZExpressions : null,
                externalSlots,
                computedSlots,
                ExternalSlotLayout.SLOT_MAJOR,
                null);
        for (int slot = safeStartSlot; slot <= safeEndSlot; slot++) {
            source.append("    out[").append(slot).append(" * n + gid] = slot").append(slot).append(";\n");
        }
        source.append("}\n");
        return new BuildResult(source.toString(), scaleTempCount, scaleRefCount);
    }

    static BuildResult buildCompiledPlanChunkCompactSlotBuffer(DfcOpenClNoiseDescriptor descriptor,
                                                               int startSlot,
                                                               int endSlot,
                                                               String[] slotCoordXExpressions,
                                                               String[] slotCoordYExpressions,
                                                               String[] slotCoordZExpressions,
                                                               boolean[] externalSlots,
                                                               DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                                               int[] slotBufferIndices,
                                                               WrapMode wrapMode) {
        int safeStartSlot = Math.max(0, startSlot);
        int safeEndSlot = Math.min(Math.max(safeStartSlot, endSlot), descriptor.slotCount - 1);
        int safeUsedSlots = descriptor.slotCount;
        boolean customCoords = hasSlotCoords(safeUsedSlots, slotCoordXExpressions, slotCoordYExpressions,
                slotCoordZExpressions);
        Map<Long, ScaleUse> scaleUses = customCoords ? Map.of() : collectScaleUses(descriptor, safeUsedSlots);
        int scaleTempCount = 0;
        int scaleRefCount = 0;
        for (ScaleUse use : scaleUses.values()) {
            if (use.count > 1) {
                use.tempIndex = scaleTempCount++;
                scaleRefCount += use.count;
            }
        }
        StringBuilder source = new StringBuilder(8192 + descriptor.totalOctaves * 512);
        source.append('\n')
                .append("__kernel void ").append(KERNEL_NAME)
                .append("(DFC_NOISE_MEM const uchar *permutations, ")
                .append("__global const double *external_slots, ")
                .append("int first_block_x, int first_block_y, int first_block_z, ")
                .append("int cell_w, int cell_h, int cells, double hoist_base, ")
                .append("__global double *out, int n) {\n")
                .append("    int gid = (int) get_global_id(0);\n")
                .append("    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) return;\n")
                .append("    double bx;\n")
                .append("    double by;\n")
                .append("    double bz;\n")
                .append("    int cell;\n")
                .append("    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z, ")
                .append("cell_w, cell_h, cells, &bx, &by, &bz, &cell)) return;\n");
        appendSlotLocals(source, descriptor, safeStartSlot, safeEndSlot, safeUsedSlots, scaleUses,
                wrapMode == WrapMode.WRAP, "    ",
                customCoords ? slotCoordXExpressions : null,
                customCoords ? slotCoordYExpressions : null,
                customCoords ? slotCoordZExpressions : null,
                externalSlots,
                computedSlots,
                ExternalSlotLayout.SLOT_MAJOR,
                slotBufferIndices);
        for (int slot = safeStartSlot; slot <= safeEndSlot; slot++) {
            source.append("    out[").append(slotBufferIndex(slotBufferIndices, slot))
                    .append(" * n + gid] = slot").append(slot).append(";\n");
        }
        source.append("}\n");
        return new BuildResult(source.toString(), scaleTempCount, scaleRefCount);
    }

    static BuildResult buildCompiledPlanWaveCompactSlotBuffer(DfcOpenClNoiseDescriptor descriptor,
                                                              boolean[] targetSlots,
                                                              String[] slotCoordXExpressions,
                                                              String[] slotCoordYExpressions,
                                                              String[] slotCoordZExpressions,
                                                              boolean[] externalSlots,
                                                              DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                                              int[] slotBufferIndices,
                                                              WrapMode wrapMode) {
        int safeUsedSlots = descriptor.slotCount;
        boolean customCoords = hasSlotCoords(safeUsedSlots, slotCoordXExpressions, slotCoordYExpressions,
                slotCoordZExpressions);
        Map<Long, ScaleUse> scaleUses = customCoords ? Map.of() : collectScaleUses(descriptor, safeUsedSlots);
        int scaleTempCount = 0;
        int scaleRefCount = 0;
        for (ScaleUse use : scaleUses.values()) {
            if (use.count > 1) {
                use.tempIndex = scaleTempCount++;
                scaleRefCount += use.count;
            }
        }
        StringBuilder source = new StringBuilder(8192 + descriptor.totalOctaves * 512);
        source.append('\n')
                .append("__kernel void ").append(KERNEL_NAME)
                .append("(DFC_NOISE_MEM const uchar *permutations, ")
                .append("__global const double *external_slots, ")
                .append("int first_block_x, int first_block_y, int first_block_z, ")
                .append("int cell_w, int cell_h, int cells, double hoist_base, ")
                .append("__global double *out, int n) {\n")
                .append("    int gid = (int) get_global_id(0);\n")
                .append("    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) return;\n")
                .append("    double bx;\n")
                .append("    double by;\n")
                .append("    double bz;\n")
                .append("    int cell;\n")
                .append("    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z, ")
                .append("cell_w, cell_h, cells, &bx, &by, &bz, &cell)) return;\n");
        appendSlotLocals(source, descriptor, targetSlots, safeUsedSlots, scaleUses,
                wrapMode == WrapMode.WRAP, "    ",
                customCoords ? slotCoordXExpressions : null,
                customCoords ? slotCoordYExpressions : null,
                customCoords ? slotCoordZExpressions : null,
                externalSlots,
                computedSlots,
                ExternalSlotLayout.SLOT_MAJOR,
                slotBufferIndices);
        for (int slot = 0; slot < safeUsedSlots; slot++) {
            if (targetSlots == null || slot >= targetSlots.length || !targetSlots[slot]) {
                continue;
            }
            source.append("    out[").append(slotBufferIndex(slotBufferIndices, slot))
                    .append(" * n + gid] = slot").append(slot).append(";\n");
        }
        source.append("}\n");
        return new BuildResult(source.toString(), scaleTempCount, scaleRefCount);
    }

    static BuildResult buildCompiledPlanAllWavesCompactSlotBuffer(DfcOpenClNoiseDescriptor descriptor,
                                                                  boolean[] targetSlots,
                                                                  String[] slotCoordXExpressions,
                                                                  String[] slotCoordYExpressions,
                                                                  String[] slotCoordZExpressions,
                                                                  boolean[] externalSlots,
                                                                  DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                                                  int[] slotBufferIndices,
                                                                  WrapMode wrapMode) {
        return buildCompiledPlanWaveCompactSlotBuffer(
                descriptor, targetSlots, slotCoordXExpressions, slotCoordYExpressions, slotCoordZExpressions,
                externalSlots, computedSlots, slotBufferIndices, wrapMode);
    }

    static BuildResult buildCompiledPlanAllWavesFinalOutput(DfcOpenClNoiseDescriptor descriptor,
                                                            boolean[] rootSlots,
                                                            byte[] slabProgram,
                                                            double[] slabConstants,
                                                            String hoistExpression,
                                                            String[] slotCoordXExpressions,
                                                            String[] slotCoordYExpressions,
                                                            String[] slotCoordZExpressions,
                                                            boolean[] externalSlots,
                                                            DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                                            WrapMode wrapMode) {
        int safeUsedSlots = descriptor.slotCount;
        boolean customCoords = hasSlotCoords(safeUsedSlots, slotCoordXExpressions, slotCoordYExpressions,
                slotCoordZExpressions);
        Map<Long, ScaleUse> scaleUses = customCoords ? Map.of() : collectScaleUses(descriptor, safeUsedSlots);
        int scaleTempCount = 0;
        int scaleRefCount = 0;
        for (ScaleUse use : scaleUses.values()) {
            if (use.count > 1) {
                use.tempIndex = scaleTempCount++;
                scaleRefCount += use.count;
            }
        }
        StringBuilder source = new StringBuilder(8192 + descriptor.totalOctaves * 512 + slabProgram.length * 96);
        source.append('\n')
                .append("__kernel void ").append(KERNEL_NAME)
                .append("(DFC_NOISE_MEM const uchar *permutations, ")
                .append("__global const double *external_slots, ")
                .append("int first_block_x, int first_block_y, int first_block_z, ")
                .append("int cell_w, int cell_h, int cells, double hoist_base, ")
                .append("__global double *out, int n) {\n")
                .append("    int gid = (int) get_global_id(0);\n")
                .append("    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) return;\n")
                .append("    double bx;\n")
                .append("    double by;\n")
                .append("    double bz;\n")
                .append("    int cell;\n")
                .append("    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z, ")
                .append("cell_w, cell_h, cells, &bx, &by, &bz, &cell)) return;\n");
        appendSlotLocals(source, descriptor, rootSlots, safeUsedSlots, scaleUses,
                wrapMode == WrapMode.WRAP, "    ",
                customCoords ? slotCoordXExpressions : null,
                customCoords ? slotCoordYExpressions : null,
                customCoords ? slotCoordZExpressions : null,
                externalSlots,
                computedSlots,
                ExternalSlotLayout.ROW_MAJOR,
                null);
        source.append("    double y_hoist = ").append(hoistExpression == null ? "0.0" : hoistExpression).append(";\n");
        appendSlabProgramToOutput(source, slabProgram, slabConstants, safeUsedSlots, "y_hoist");
        source.append("}\n");
        return new BuildResult(source.toString(), scaleTempCount, scaleRefCount);
    }

    static BuildResult buildCompiledPlanFinalOutputFromSlotBuffer(DfcOpenClNoiseDescriptor descriptor,
                                                                  boolean[] rootSlots,
                                                                  byte[] slabProgram,
                                                                  double[] slabConstants,
                                                                  String hoistExpression,
                                                                  String[] slotCoordXExpressions,
                                                                  String[] slotCoordYExpressions,
                                                                  String[] slotCoordZExpressions,
                                                                  boolean[] slotBufferInputSlots,
                                                                  DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                                                  int[] slotBufferIndices,
                                                                  WrapMode wrapMode) {
        int safeUsedSlots = descriptor.slotCount;
        boolean customCoords = hasSlotCoords(safeUsedSlots, slotCoordXExpressions, slotCoordYExpressions,
                slotCoordZExpressions);
        Map<Long, ScaleUse> scaleUses = customCoords ? Map.of() : collectScaleUses(descriptor, safeUsedSlots);
        int scaleTempCount = 0;
        int scaleRefCount = 0;
        for (ScaleUse use : scaleUses.values()) {
            if (use.count > 1) {
                use.tempIndex = scaleTempCount++;
                scaleRefCount += use.count;
            }
        }
        StringBuilder source = new StringBuilder(8192 + descriptor.totalOctaves * 512 + slabProgram.length * 96);
        source.append('\n')
                .append("__kernel void ").append(KERNEL_NAME)
                .append("(DFC_NOISE_MEM const uchar *permutations, ")
                .append("__global const double *external_slots, ")
                .append("int first_block_x, int first_block_y, int first_block_z, ")
                .append("int cell_w, int cell_h, int cells, double hoist_base, ")
                .append("__global double *out, int n) {\n")
                .append("    int gid = (int) get_global_id(0);\n")
                .append("    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) return;\n")
                .append("    double bx;\n")
                .append("    double by;\n")
                .append("    double bz;\n")
                .append("    int cell;\n")
                .append("    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z, ")
                .append("cell_w, cell_h, cells, &bx, &by, &bz, &cell)) return;\n");
        appendSlotLocals(source, descriptor, rootSlots, safeUsedSlots, scaleUses,
                wrapMode == WrapMode.WRAP, "    ",
                customCoords ? slotCoordXExpressions : null,
                customCoords ? slotCoordYExpressions : null,
                customCoords ? slotCoordZExpressions : null,
                slotBufferInputSlots,
                computedSlots,
                ExternalSlotLayout.SLOT_MAJOR,
                slotBufferIndices);
        source.append("    double y_hoist = ").append(hoistExpression == null ? "0.0" : hoistExpression).append(";\n");
        appendSlabProgramToOutput(source, slabProgram, slabConstants, safeUsedSlots, "y_hoist");
        source.append("}\n");
        return new BuildResult(source.toString(), scaleTempCount, scaleRefCount);
    }

    private static void appendBody(StringBuilder source, DfcOpenClNoiseDescriptor descriptor, int safeUsedSlots,
                                   Map<Long, ScaleUse> scaleUses, boolean wrapAxis, String indent) {
        for (ScaleUse use : scaleUses.values()) {
            if (use.tempIndex >= 0) {
                appendScaledCoordDeclaration(source, indent, "sx", use, "bx", wrapAxis);
                appendScaledCoordDeclaration(source, indent, "sy", use, "by", wrapAxis);
                appendScaledCoordDeclaration(source, indent, "sz", use, "bz", wrapAxis);
            }
        }
        for (int slot = 0; slot < safeUsedSlots; slot++) {
            appendSlot(source, descriptor, slot, scaleUses, wrapAxis, indent);
        }
    }

    private static void appendSlotLocals(StringBuilder source, DfcOpenClNoiseDescriptor descriptor, int safeUsedSlots,
                                         Map<Long, ScaleUse> scaleUses, boolean wrapAxis, String indent,
                                         String[] slotCoordXExpressions,
                                         String[] slotCoordYExpressions,
                                         String[] slotCoordZExpressions,
                                         boolean[] externalSlots,
                                         DfcOpenClRuntime.ComputedSlot[] computedSlots) {
        for (ScaleUse use : scaleUses.values()) {
            if (use.tempIndex >= 0) {
                appendScaledCoordDeclaration(source, indent, "sx", use, "bx", wrapAxis);
                appendScaledCoordDeclaration(source, indent, "sy", use, "by", wrapAxis);
                appendScaledCoordDeclaration(source, indent, "sz", use, "bz", wrapAxis);
            }
        }
        boolean[] emitted = new boolean[safeUsedSlots];
        boolean[] visiting = new boolean[safeUsedSlots];
        for (int slot = 0; slot < safeUsedSlots; slot++) {
            appendSlotLocal(source, descriptor, safeUsedSlots, scaleUses, wrapAxis, indent,
                    slotCoordXExpressions, slotCoordYExpressions, slotCoordZExpressions,
                    externalSlots, computedSlots, ExternalSlotLayout.ROW_MAJOR, null, emitted, visiting, slot);
        }
    }

    private static void appendSlotLocals(StringBuilder source, DfcOpenClNoiseDescriptor descriptor,
                                         int startSlot, int endSlot, int safeUsedSlots,
                                         Map<Long, ScaleUse> scaleUses, boolean wrapAxis, String indent,
                                         String[] slotCoordXExpressions,
                                         String[] slotCoordYExpressions,
                                         String[] slotCoordZExpressions,
                                         boolean[] externalSlots,
                                         DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                         ExternalSlotLayout externalSlotLayout,
                                         int[] slotBufferIndices) {
        for (ScaleUse use : scaleUses.values()) {
            if (use.tempIndex >= 0) {
                appendScaledCoordDeclaration(source, indent, "sx", use, "bx", wrapAxis);
                appendScaledCoordDeclaration(source, indent, "sy", use, "by", wrapAxis);
                appendScaledCoordDeclaration(source, indent, "sz", use, "bz", wrapAxis);
            }
        }
        boolean[] emitted = new boolean[safeUsedSlots];
        boolean[] visiting = new boolean[safeUsedSlots];
        for (int slot = startSlot; slot <= endSlot; slot++) {
            appendSlotLocal(source, descriptor, safeUsedSlots, scaleUses, wrapAxis, indent,
                    slotCoordXExpressions, slotCoordYExpressions, slotCoordZExpressions,
                    externalSlots, computedSlots, externalSlotLayout, slotBufferIndices, emitted, visiting, slot);
        }
    }

    private static void appendSlotLocals(StringBuilder source, DfcOpenClNoiseDescriptor descriptor,
                                         boolean[] targetSlots, int safeUsedSlots,
                                         Map<Long, ScaleUse> scaleUses, boolean wrapAxis, String indent,
                                         String[] slotCoordXExpressions,
                                         String[] slotCoordYExpressions,
                                         String[] slotCoordZExpressions,
                                         boolean[] externalSlots,
                                         DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                         ExternalSlotLayout externalSlotLayout,
                                         int[] slotBufferIndices) {
        for (ScaleUse use : scaleUses.values()) {
            if (use.tempIndex >= 0) {
                appendScaledCoordDeclaration(source, indent, "sx", use, "bx", wrapAxis);
                appendScaledCoordDeclaration(source, indent, "sy", use, "by", wrapAxis);
                appendScaledCoordDeclaration(source, indent, "sz", use, "bz", wrapAxis);
            }
        }
        boolean[] emitted = new boolean[safeUsedSlots];
        boolean[] visiting = new boolean[safeUsedSlots];
        for (int slot = 0; slot < safeUsedSlots; slot++) {
            if (targetSlots == null || slot >= targetSlots.length || !targetSlots[slot]) {
                continue;
            }
            appendSlotLocal(source, descriptor, safeUsedSlots, scaleUses, wrapAxis, indent,
                    slotCoordXExpressions, slotCoordYExpressions, slotCoordZExpressions,
                    externalSlots, computedSlots, externalSlotLayout, slotBufferIndices, emitted, visiting, slot);
        }
    }

    private static void appendSlotLocal(StringBuilder source, DfcOpenClNoiseDescriptor descriptor, int safeUsedSlots,
                                        Map<Long, ScaleUse> scaleUses, boolean wrapAxis, String indent,
                                        String[] slotCoordXExpressions,
                                        String[] slotCoordYExpressions,
                                        String[] slotCoordZExpressions,
                                        boolean[] externalSlots,
                                        DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                        ExternalSlotLayout externalSlotLayout,
                                        int[] slotBufferIndices,
                                        boolean[] emitted,
                                        boolean[] visiting,
                                        int slot) {
        if (slot < 0 || slot >= safeUsedSlots || emitted[slot]) {
            return;
        }
        if (visiting[slot]) {
            throw new IllegalStateException("cyclic generated OpenCL slot dependency at slot " + slot);
        }
        visiting[slot] = true;
        DfcOpenClRuntime.ComputedSlot computed = computedSlot(computedSlots, slot);
        try {
            if (isExternalSlot(externalSlots, slot)) {
                source.append(indent).append("double slot").append(slot).append(" = ");
                appendExternalSlotRead(source, externalSlotLayout, safeUsedSlots, slotBufferIndices, slot);
                source.append(";\n");
                emitted[slot] = true;
                return;
            }

            if (computed != null) {
                for (int dependency : slotDependencies(computed.slabProgram(), safeUsedSlots)) {
                    appendSlotLocal(source, descriptor, safeUsedSlots, scaleUses, wrapAxis, indent,
                            slotCoordXExpressions, slotCoordYExpressions, slotCoordZExpressions,
                            externalSlots, computedSlots, externalSlotLayout, slotBufferIndices,
                            emitted, visiting, dependency);
                }
                for (int dependency : slotExpressionDependencies(computed.hoistExpression(), slot, safeUsedSlots)) {
                    appendSlotLocal(source, descriptor, safeUsedSlots, scaleUses, wrapAxis, indent,
                            slotCoordXExpressions, slotCoordYExpressions, slotCoordZExpressions,
                            externalSlots, computedSlots, externalSlotLayout, slotBufferIndices,
                            emitted, visiting, dependency);
                }
                String hoistVar = "slot" + slot + "_hoist";
                source.append(indent).append("double ").append(hoistVar).append(" = ")
                        .append(computed.hoistExpression() == null ? "0.0" : computed.hoistExpression()).append(";\n");
                appendSlabProgramToVariable(source, computed.slabProgram(), computed.slabConstants(),
                        safeUsedSlots, hoistVar, "slot" + slot, "slot" + slot + "_", indent);
                emitted[slot] = true;
                return;
            }

            for (int dependency : slotCoordinateDependencies(
                    slotCoordXExpressions, slotCoordYExpressions, slotCoordZExpressions, slot, safeUsedSlots)) {
                appendSlotLocal(source, descriptor, safeUsedSlots, scaleUses, wrapAxis, indent,
                        slotCoordXExpressions, slotCoordYExpressions, slotCoordZExpressions,
                        externalSlots, computedSlots, externalSlotLayout, slotBufferIndices,
                        emitted, visiting, dependency);
            }

            source.append(indent).append("double slot").append(slot).append(" = ");
            if (descriptor.isBlendedSlot(slot)) {
                appendBlendedSlotExpression(source, descriptor, descriptor.blendedSlot(slot),
                        coordExpression(slotCoordXExpressions, slot, "bx"),
                        coordExpression(slotCoordYExpressions, slot, "by"),
                        coordExpression(slotCoordZExpressions, slot, "bz"),
                        indent);
            } else {
                source.append(d(descriptor.slotValueFactors[slot])).append(" * (0.0");
                if (slotCoordXExpressions != null) {
                    appendSlotOctaves(source, descriptor, slot,
                            slotCoordXExpressions[slot], slotCoordYExpressions[slot], slotCoordZExpressions[slot],
                            wrapAxis, indent);
                } else {
                    appendSlotOctaves(source, descriptor, slot, scaleUses, wrapAxis, indent);
                }
                source.append(")");
            }
            source.append(";\n");
            emitted[slot] = true;
        } finally {
            visiting[slot] = false;
        }
    }

    private static void appendExternalSlotRead(StringBuilder source, ExternalSlotLayout layout, int safeUsedSlots,
                                               int[] slotBufferIndices, int slot) {
        if (layout == ExternalSlotLayout.SLOT_MAJOR) {
            source.append("external_slots[").append(slotBufferIndex(slotBufferIndices, slot)).append(" * n + gid]");
        } else {
            source.append("external_slots[gid * ").append(safeUsedSlots).append(" + ").append(slot).append("]");
        }
    }

    private static int slotBufferIndex(int[] slotBufferIndices, int slot) {
        if (slotBufferIndices == null) {
            return slot;
        }
        if (slot < 0 || slot >= slotBufferIndices.length || slotBufferIndices[slot] < 0) {
            throw new IllegalArgumentException("slot " + slot + " has no compact slot buffer index");
        }
        return slotBufferIndices[slot];
    }

    private static void appendSlot(StringBuilder source, DfcOpenClNoiseDescriptor descriptor, int slot,
                                   Map<Long, ScaleUse> scaleUses, boolean wrapAxis, String indent) {
        source.append(indent).append("value += ").append(d(descriptor.slotValueFactors[slot])).append(" * (0.0");
        appendSlotOctaves(source, descriptor, slot, scaleUses, wrapAxis, indent);
        source.append(");\n");
    }

    private static void appendBlendedSlotExpression(StringBuilder source,
                                                    DfcOpenClNoiseDescriptor descriptor,
                                                    DfcOpenClNoiseDescriptor.BlendedSlot slot,
                                                    String bxExpression,
                                                    String byExpression,
                                                    String bzExpression,
                                                    String indent) {
        source.append("(0.0");
        appendBlendedMainContribution(source, descriptor, slot, bxExpression, byExpression, bzExpression, indent);
        source.append(")");
    }

    private static void appendBlendedMainContribution(StringBuilder source,
                                                      DfcOpenClNoiseDescriptor descriptor,
                                                      DfcOpenClNoiseDescriptor.BlendedSlot slot,
                                                      String bxExpression,
                                                      String byExpression,
                                                      String bzExpression,
                                                      String indent) {
        String px = "(" + bxExpression + ")";
        String py = "(" + byExpression + ")";
        String pz = "(" + bzExpression + ")";
        String x = "(" + px + " * " + d(slot.xzMultiplier()) + ")";
        String y = "(" + py + " * " + d(slot.yMultiplier()) + ")";
        String z = "(" + pz + " * " + d(slot.xzMultiplier()) + ")";
        String mainX = "(" + x + " / " + d(slot.xzFactor()) + ")";
        String mainY = "(" + y + " / " + d(slot.yFactor()) + ")";
        String mainZ = "(" + z + " / " + d(slot.xzFactor()) + ")";
        String smearY = "(" + d(slot.yMultiplier()) + " * " + d(slot.smearScaleMultiplier()) + ")";
        String mainYScale = "(" + smearY + " / " + d(slot.yFactor()) + ")";

        String mainExpr = blendedSumExpression(descriptor, slot.mainOctaves(),
                mainX, mainY, mainZ, mainYScale, mainY, indent);
        String blend = "(((" + mainExpr + ") / 10.0 + 1.0) / 2.0)";
        String minExpr = blendedSumExpression(descriptor, slot.minLimitOctaves(),
                x, y, z, smearY, y, indent);
        String maxExpr = blendedSumExpression(descriptor, slot.maxLimitOctaves(),
                x, y, z, smearY, y, indent);
        source.append(" + (dfc_clamped_lerp((")
                .append(minExpr).append(") / 512.0, (")
                .append(maxExpr).append(") / 512.0, ")
                .append(blend).append(") / 128.0)");
    }

    private static String blendedSumExpression(DfcOpenClNoiseDescriptor descriptor, int[] octaveIndices,
                                               String xExpression, String yExpression, String zExpression,
                                               String yScaleExpression, String yMaxExpression,
                                               String indent) {
        StringBuilder out = new StringBuilder();
        out.append("0.0");
        if (octaveIndices == null) {
            return out.toString();
        }
        for (int octave = 0; octave < octaveIndices.length; octave++) {
            int index = octaveIndices[octave];
            if (index < 0) {
                continue;
            }
            double scale = 1.0D / (1L << octave);
            int origin = index * 3;
            out.append('\n').append(indent).append("        + ")
                    .append("dfc_perlin_sample_5(permutations + ")
                    .append(index * DfcOpenClNoiseDescriptor.PERMUTATION_STRIDE)
                    .append(", ")
                    .append(d(descriptor.origins[origin])).append(", ")
                    .append(d(descriptor.origins[origin + 1])).append(", ")
                    .append(d(descriptor.origins[origin + 2])).append(", ");
            appendWrappedScaledExpression(out, xExpression, scale);
            out.append(", ");
            appendWrappedScaledExpression(out, yExpression, scale);
            out.append(", ");
            appendWrappedScaledExpression(out, zExpression, scale);
            out.append(", (").append(yScaleExpression).append(" * ").append(d(scale)).append(")")
                    .append(", (").append(yMaxExpression).append(" * ").append(d(scale)).append(")) / ")
                    .append(d(scale));
        }
        return out.toString();
    }

    private static String coordExpression(String[] expressions, int slot, String fallback) {
        return expressions != null && slot >= 0 && slot < expressions.length && expressions[slot] != null
                ? expressions[slot]
                : fallback;
    }

    private static void appendSlotOctaves(StringBuilder source, DfcOpenClNoiseDescriptor descriptor, int slot,
                                          Map<Long, ScaleUse> scaleUses, boolean wrapAxis, String indent) {
        int branchBase = slot * descriptor.branchesPerSlot;
        for (int branch = 0; branch < descriptor.branchesPerSlot; branch++) {
            int branchIndex = branchBase + branch;
            int octaveOffset = descriptor.branchOctaveOffsets[branchIndex];
            int octaveCount = descriptor.branchOctaveCounts[branchIndex];
            double coordScale = descriptor.branchCoordScales[branchIndex];
            for (int octave = 0; octave < octaveCount; octave++) {
                int index = octaveOffset + octave;
                double inputScale = coordScale * descriptor.inputFactors[index];
                int origin = index * 3;
                source.append('\n').append(indent).append("        + ")
                        .append(d(descriptor.ampFactors[index]))
                        .append(" * dfc_perlin_sample(permutations + ")
                        .append(index * DfcOpenClNoiseDescriptor.PERMUTATION_STRIDE)
                        .append(", ")
                        .append(d(descriptor.origins[origin])).append(", ")
                        .append(d(descriptor.origins[origin + 1])).append(", ")
                        .append(d(descriptor.origins[origin + 2])).append(", ");
                appendScaledCoords(source, inputScale, scaleUses, wrapAxis);
                source.append(")");
            }
        }
    }

    private static void appendSlotOctaves(StringBuilder source, DfcOpenClNoiseDescriptor descriptor, int slot,
                                          String coordXExpression, String coordYExpression, String coordZExpression,
                                          boolean wrapAxis, String indent) {
        int branchBase = slot * descriptor.branchesPerSlot;
        for (int branch = 0; branch < descriptor.branchesPerSlot; branch++) {
            int branchIndex = branchBase + branch;
            int octaveOffset = descriptor.branchOctaveOffsets[branchIndex];
            int octaveCount = descriptor.branchOctaveCounts[branchIndex];
            double coordScale = descriptor.branchCoordScales[branchIndex];
            for (int octave = 0; octave < octaveCount; octave++) {
                int index = octaveOffset + octave;
                double inputScale = coordScale * descriptor.inputFactors[index];
                int origin = index * 3;
                source.append('\n').append(indent).append("        + ")
                        .append(d(descriptor.ampFactors[index]))
                        .append(" * dfc_perlin_sample(permutations + ")
                        .append(index * DfcOpenClNoiseDescriptor.PERMUTATION_STRIDE)
                        .append(", ")
                        .append(d(descriptor.origins[origin])).append(", ")
                        .append(d(descriptor.origins[origin + 1])).append(", ")
                        .append(d(descriptor.origins[origin + 2])).append(", ");
                appendScaledCoordExpression(source, coordXExpression, inputScale, wrapAxis);
                source.append(", ");
                appendScaledCoordExpression(source, coordYExpression, inputScale, wrapAxis);
                source.append(", ");
                appendScaledCoordExpression(source, coordZExpression, inputScale, wrapAxis);
                source.append(")");
            }
        }
    }

    private static void appendSlabProgramToOutput(StringBuilder source, byte[] program, double[] constants,
                                                  int slotCount, String hoistVar) {
        appendSlabProgram(source, program, constants, slotCount, hoistVar, "out[gid]", "", "    ");
    }

    private static void appendSlabProgramToVariable(StringBuilder source, byte[] program, double[] constants,
                                                    int slotCount, String hoistVar, String outputVar,
                                                    String prefix, String indent) {
        appendSlabProgram(source, program, constants, slotCount, hoistVar, outputVar, prefix, indent);
    }

    private static void appendSlabProgram(StringBuilder source, byte[] program, double[] constants, int slotCount,
                                          String hoistVar, String outputTarget, String prefix, String indent) {
        String stk = prefix + "stk";
        String sp = prefix + "sp";
        source.append(indent).append("double ").append(stk).append("[DFC_SLAB_STACK];\n")
                .append(indent).append("int ").append(sp).append(" = 0;\n");
        int temp = 0;
        for (int pc = 0; pc < program.length;) {
            int op = program[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST -> {
                    int idx = readU16(program, pc);
                    pc += 2;
                    requireConst(constants, idx);
                    source.append(indent).append(stk).append("[").append(sp).append("++] = ")
                            .append(d(constants[idx])).append(";\n");
                }
                case OP_PUSH_SLOT -> {
                    int slot = program[pc++] & 0xFF;
                    if (slot < 0 || slot >= slotCount) {
                        throw new IllegalArgumentException("compiled slab program references missing slot " + slot);
                    }
                    source.append(indent).append(stk).append("[").append(sp).append("++] = slot")
                            .append(slot).append(";\n");
                }
                case OP_COND_NEG_SCALE -> {
                    int idx = readU16(program, pc);
                    pc += 2;
                    requireConst(constants, idx);
                    int t = temp++;
                    source.append(indent).append("double ").append(prefix).append("x").append(t)
                            .append(" = ").append(stk).append("[--").append(sp).append("];\n")
                            .append(indent).append(stk).append("[").append(sp).append("++] = ")
                            .append(prefix).append("x").append(t).append(" > 0.0 ? ")
                            .append(prefix).append("x").append(t)
                            .append(" : ").append(prefix).append("x").append(t).append(" * ")
                            .append(d(constants[idx])).append(";\n");
                }
                case OP_Y_CLAMPED_GRADIENT -> {
                    int fromY = readU16(program, pc); pc += 2;
                    int toY = readU16(program, pc); pc += 2;
                    int fromValue = readU16(program, pc); pc += 2;
                    int toValue = readU16(program, pc); pc += 2;
                    requireConst(constants, fromY);
                    requireConst(constants, toY);
                    requireConst(constants, fromValue);
                    requireConst(constants, toValue);
                    source.append(indent).append(stk).append("[").append(sp)
                            .append("++] = dfc_clamped_map(by, ")
                            .append(d(constants[fromY])).append(", ")
                            .append(d(constants[toY])).append(", ")
                            .append(d(constants[fromValue])).append(", ")
                            .append(d(constants[toValue])).append(");\n");
                }
                case OP_RANGE_CHOICE -> {
                    int min = readU16(program, pc); pc += 2;
                    int max = readU16(program, pc); pc += 2;
                    requireConst(constants, min);
                    requireConst(constants, max);
                    int t = temp++;
                    source.append(indent).append("double ").append(prefix).append("when_out").append(t)
                            .append(" = ").append(stk).append("[--").append(sp).append("];\n")
                            .append(indent).append("double ").append(prefix).append("when_in").append(t)
                            .append(" = ").append(stk).append("[--").append(sp).append("];\n")
                            .append(indent).append("double ").append(prefix).append("input").append(t)
                            .append(" = ").append(stk).append("[--").append(sp).append("];\n")
                            .append(indent).append(stk).append("[").append(sp).append("++] = ")
                            .append(prefix).append("input").append(t).append(" >= ")
                            .append(d(constants[min])).append(" && ").append(prefix).append("input").append(t)
                            .append(" < ").append(d(constants[max])).append(" ? ")
                            .append(prefix).append("when_in").append(t)
                            .append(" : ").append(prefix).append("when_out").append(t).append(";\n");
                }
                case OP_BLOCK_X -> source.append(indent).append(stk).append("[").append(sp).append("++] = bx;\n");
                case OP_BLOCK_Y -> source.append(indent).append(stk).append("[").append(sp).append("++] = by;\n");
                case OP_BLOCK_Z -> source.append(indent).append(stk).append("[").append(sp).append("++] = bz;\n");
                case OP_HOIST -> source.append(indent).append(stk).append("[").append(sp).append("++] = ")
                        .append(hoistVar).append(";\n");
                case OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX -> {
                    int t = temp++;
                    source.append(indent).append("double ").append(prefix).append("r").append(t)
                            .append(" = ").append(stk).append("[--").append(sp).append("];\n")
                            .append(indent).append("double ").append(prefix).append("l").append(t)
                            .append(" = ").append(stk).append("[--").append(sp).append("];\n")
                            .append(indent).append(stk).append("[").append(sp).append("++] = ");
                    appendBinary(source, op, prefix + "l" + t, prefix + "r" + t);
                    source.append(";\n");
                }
                case OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                    int t = temp++;
                    source.append(indent).append("double ").append(prefix).append("x").append(t)
                            .append(" = ").append(stk).append("[--").append(sp).append("];\n")
                            .append(indent).append(stk).append("[").append(sp).append("++] = ");
                    appendUnary(source, op, prefix + "x" + t);
                    source.append(";\n");
                }
                default -> throw new IllegalArgumentException("unsupported compiled slab opcode " + op);
            }
        }
        source.append(indent);
        if (isSimpleIdentifier(outputTarget)) {
            source.append("double ");
        }
        source.append(outputTarget).append(" = ").append(sp).append(" == 1 ? ")
                .append(stk).append("[0] : 0.0;\n");
    }

    private static void appendBinary(StringBuilder source, int op, String left, String right) {
        switch (op) {
            case OP_ADD -> source.append(left).append(" + ").append(right);
            case OP_SUB -> source.append(left).append(" - ").append(right);
            case OP_MUL -> source.append(left).append(" * ").append(right);
            case OP_DIV -> source.append(left).append(" / ").append(right);
            case OP_MIN -> source.append("dfc_java_min(").append(left).append(", ").append(right).append(")");
            case OP_MAX -> source.append("dfc_java_max(").append(left).append(", ").append(right).append(")");
            default -> throw new IllegalArgumentException("not a binary opcode " + op);
        }
    }

    private static void appendUnary(StringBuilder source, int op, String value) {
        switch (op) {
            case OP_NEG -> source.append("-").append(value);
            case OP_ABS -> source.append("fabs(").append(value).append(")");
            case OP_SQUARE -> source.append(value).append(" * ").append(value);
            case OP_SQUEEZE -> source.append("dfc_squeeze(").append(value).append(")");
            default -> throw new IllegalArgumentException("not a unary opcode " + op);
        }
    }

    private static DfcOpenClRuntime.ComputedSlot computedSlot(DfcOpenClRuntime.ComputedSlot[] computedSlots,
                                                              int slot) {
        return computedSlots != null && slot >= 0 && slot < computedSlots.length ? computedSlots[slot] : null;
    }

    private static int[] slotDependencies(byte[] program, int slotCount) {
        boolean[] seen = new boolean[slotCount];
        int count = 0;
        for (int pc = 0; pc < program.length;) {
            int op = program[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST, OP_COND_NEG_SCALE -> pc += 2;
                case OP_PUSH_SLOT -> {
                    int slot = program[pc++] & 0xFF;
                    if (slot < 0 || slot >= slotCount) {
                        throw new IllegalArgumentException("compiled slab program references missing slot " + slot);
                    }
                    if (!seen[slot]) {
                        seen[slot] = true;
                        count++;
                    }
                }
                case OP_Y_CLAMPED_GRADIENT -> pc += 8;
                case OP_RANGE_CHOICE -> pc += 4;
                case OP_BLOCK_X, OP_BLOCK_Y, OP_BLOCK_Z, OP_HOIST,
                     OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX,
                     OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                }
                default -> throw new IllegalArgumentException("unsupported compiled slab opcode " + op);
            }
        }
        int[] dependencies = new int[count];
        int next = 0;
        for (int slot = 0; slot < seen.length; slot++) {
            if (seen[slot]) {
                dependencies[next++] = slot;
            }
        }
        return dependencies;
    }

    private static int[] slotCoordinateDependencies(String[] slotCoordXExpressions,
                                                    String[] slotCoordYExpressions,
                                                    String[] slotCoordZExpressions,
                                                    int slot,
                                                    int slotCount) {
        boolean[] seen = new boolean[slotCount];
        markSlotExpressionDependencies(expressionAt(slotCoordXExpressions, slot), seen);
        markSlotExpressionDependencies(expressionAt(slotCoordYExpressions, slot), seen);
        markSlotExpressionDependencies(expressionAt(slotCoordZExpressions, slot), seen);
        seen[slot] = false;
        int count = 0;
        for (boolean value : seen) {
            if (value) {
                count++;
            }
        }
        int[] out = new int[count];
        int next = 0;
        for (int i = 0; i < seen.length; i++) {
            if (seen[i]) {
                out[next++] = i;
            }
        }
        return out;
    }

    private static int[] slotExpressionDependencies(String expression, int slot, int slotCount) {
        boolean[] seen = new boolean[slotCount];
        markSlotExpressionDependencies(expression, seen);
        if (slot >= 0 && slot < seen.length) {
            seen[slot] = false;
        }
        int count = 0;
        for (boolean value : seen) {
            if (value) {
                count++;
            }
        }
        int[] out = new int[count];
        int next = 0;
        for (int i = 0; i < seen.length; i++) {
            if (seen[i]) {
                out[next++] = i;
            }
        }
        return out;
    }

    private static String expressionAt(String[] expressions, int slot) {
        return expressions != null && slot >= 0 && slot < expressions.length ? expressions[slot] : null;
    }

    private static void markSlotExpressionDependencies(String expression, boolean[] seen) {
        if (expression == null || expression.indexOf("slot") < 0) {
            return;
        }
        for (int i = 0; i < expression.length() - 4; i++) {
            if (expression.charAt(i) != 's'
                    || expression.charAt(i + 1) != 'l'
                    || expression.charAt(i + 2) != 'o'
                    || expression.charAt(i + 3) != 't') {
                continue;
            }
            int digit = i + 4;
            if (digit >= expression.length() || !Character.isDigit(expression.charAt(digit))) {
                continue;
            }
            int value = 0;
            int end = digit;
            while (end < expression.length() && Character.isDigit(expression.charAt(end))) {
                value = value * 10 + (expression.charAt(end) - '0');
                end++;
            }
            if (value >= 0 && value < seen.length) {
                seen[value] = true;
            }
            i = end - 1;
        }
    }

    private static boolean isSimpleIdentifier(String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        for (int i = 0; i < target.length(); i++) {
            char ch = target.charAt(i);
            if (i == 0 ? !Character.isJavaIdentifierStart(ch) : !Character.isJavaIdentifierPart(ch)) {
                return false;
            }
        }
        return true;
    }

    private static int readU16(byte[] bytes, int offset) {
        if (offset < 0 || offset + 1 >= bytes.length) {
            throw new IllegalArgumentException("truncated compiled slab program");
        }
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static void requireConst(double[] constants, int idx) {
        if (idx < 0 || idx >= constants.length) {
            throw new IllegalArgumentException("compiled slab program references missing const " + idx);
        }
    }

    private static Map<Long, ScaleUse> collectScaleUses(DfcOpenClNoiseDescriptor descriptor, int safeUsedSlots) {
        Map<Long, ScaleUse> uses = new LinkedHashMap<>();
        for (int slot = 0; slot < safeUsedSlots; slot++) {
            int branchBase = slot * descriptor.branchesPerSlot;
            for (int branch = 0; branch < descriptor.branchesPerSlot; branch++) {
                int branchIndex = branchBase + branch;
                int octaveOffset = descriptor.branchOctaveOffsets[branchIndex];
                int octaveCount = descriptor.branchOctaveCounts[branchIndex];
                double coordScale = descriptor.branchCoordScales[branchIndex];
                for (int octave = 0; octave < octaveCount; octave++) {
                    int index = octaveOffset + octave;
                    double inputScale = coordScale * descriptor.inputFactors[index];
                    long key = scaleKey(inputScale);
                    ScaleUse use = uses.computeIfAbsent(key, ignored -> new ScaleUse(inputScale));
                    use.count++;
                }
            }
        }
        return uses;
    }

    private static boolean hasSlotCoords(int safeUsedSlots, String[] coordX, String[] coordY, String[] coordZ) {
        if (coordX == null || coordY == null || coordZ == null
                || coordX.length < safeUsedSlots || coordY.length < safeUsedSlots || coordZ.length < safeUsedSlots) {
            return false;
        }
        for (int i = 0; i < safeUsedSlots; i++) {
            if (coordX[i] == null || coordY[i] == null || coordZ[i] == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExternalSlot(boolean[] externalSlots, int slot) {
        return externalSlots != null && slot >= 0 && slot < externalSlots.length && externalSlots[slot];
    }

    private static void appendScaledCoordDeclaration(StringBuilder source, String indent, String varPrefix,
                                                     ScaleUse use, String coordName, boolean wrapAxis) {
        source.append(indent).append("double ").append(varPrefix).append(use.tempIndex).append(" = ");
        appendScaledCoord(source, coordName, use.scale, wrapAxis);
        source.append(";\n");
    }

    private static void appendScaledCoords(StringBuilder source, double inputScale, Map<Long, ScaleUse> scaleUses,
                                           boolean wrapAxis) {
        ScaleUse use = scaleUses.get(scaleKey(inputScale));
        if (use != null && use.tempIndex >= 0) {
            source.append("sx").append(use.tempIndex)
                    .append(", sy").append(use.tempIndex)
                    .append(", sz").append(use.tempIndex);
            return;
        }
        appendScaledCoord(source, "bx", inputScale, wrapAxis);
        source.append(", ");
        appendScaledCoord(source, "by", inputScale, wrapAxis);
        source.append(", ");
        appendScaledCoord(source, "bz", inputScale, wrapAxis);
    }

    private static void appendScaledCoord(StringBuilder source, String coordName, double inputScale,
                                          boolean wrapAxis) {
        if (wrapAxis) {
            source.append("dfc_wrap_axis(").append(coordName).append(" * ").append(d(inputScale)).append(")");
        } else {
            source.append("(").append(coordName).append(" * ").append(d(inputScale)).append(")");
        }
    }

    private static void appendScaledCoordExpression(StringBuilder source, String coordExpression, double inputScale,
                                                    boolean wrapAxis) {
        if (wrapAxis) {
            source.append("dfc_wrap_axis((").append(coordExpression).append(") * ").append(d(inputScale)).append(")");
        } else {
            source.append("((").append(coordExpression).append(") * ").append(d(inputScale)).append(")");
        }
    }

    private static void appendWrappedScaledExpression(StringBuilder source, String coordExpression, double inputScale) {
        source.append("dfc_wrap_axis((").append(coordExpression).append(") * ").append(d(inputScale)).append(")");
    }

    private static long scaleKey(double value) {
        return Double.doubleToLongBits(value == 0.0D ? 0.0D : value);
    }

    private static String d(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite generated OpenCL literal: " + value);
        }
        return Double.toString(value);
    }

    private static final class ScaleUse {
        final double scale;
        int count;
        int tempIndex = -1;

        ScaleUse(double scale) {
            this.scale = scale;
        }
    }
}
