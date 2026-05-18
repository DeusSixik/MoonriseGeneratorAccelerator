package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import java.util.LinkedHashMap;
import java.util.Map;

final class DfcOpenClGeneratedNoiseSource {
    static final String KERNEL_NAME = "dfc_generated_real_noise";

    private DfcOpenClGeneratedNoiseSource() {
    }

    enum WrapMode {
        WRAP,
        NOWRAP
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

    private static void appendSlot(StringBuilder source, DfcOpenClNoiseDescriptor descriptor, int slot,
                                   Map<Long, ScaleUse> scaleUses, boolean wrapAxis, String indent) {
        source.append(indent).append("value += ").append(d(descriptor.slotValueFactors[slot])).append(" * (0.0");
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
        source.append(");\n");
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
