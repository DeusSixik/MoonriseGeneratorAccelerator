#ifndef DFC_OPENCL_SLAB_VM_CL
#define DFC_OPENCL_SLAB_VM_CL

#define DFC_OP_PUSH_CONST 1
#define DFC_OP_PUSH_SLOT 2
#define DFC_OP_COND_NEG_SCALE 3
#define DFC_OP_Y_CLAMPED_GRADIENT 4
#define DFC_OP_RANGE_CHOICE 5
#define DFC_OP_RANGE_CHOICE_JUMP 6
#define DFC_OP_JUMP 7
#define DFC_OP_BLOCK_X 16
#define DFC_OP_BLOCK_Y 17
#define DFC_OP_BLOCK_Z 18
#define DFC_OP_HOIST 19
#define DFC_OP_ADD 32
#define DFC_OP_SUB 33
#define DFC_OP_MUL 34
#define DFC_OP_DIV 35
#define DFC_OP_MIN 36
#define DFC_OP_MAX 37
#define DFC_OP_NEG 48
#define DFC_OP_ABS 49
#define DFC_OP_SQUARE 50
#define DFC_OP_SQUEEZE 51

#define DFC_SLAB_STACK 192
#define DFC_SLAB_LAYOUT_Y_HOIST 0
#define DFC_PERM_STRIDE 512

inline ushort dfc_read_u16(__global const uchar *bc, int pc) {
    return (ushort) bc[pc] | (ushort) ((ushort) bc[pc + 1] << 8);
}

inline int dfc_read_i32(__global const uchar *bc, int pc) {
    return ((int) bc[pc])
            | (((int) bc[pc + 1]) << 8)
            | (((int) bc[pc + 2]) << 16)
            | (((int) bc[pc + 3]) << 24);
}

inline double dfc_java_min(double l, double r) {
    if (isnan(l) || isnan(r)) {
        return NAN;
    }
    if (l == 0.0 && r == 0.0) {
        return (signbit(l) || signbit(r)) ? -0.0 : 0.0;
    }
    return l <= r ? l : r;
}

inline double dfc_java_max(double l, double r) {
    if (isnan(l) || isnan(r)) {
        return NAN;
    }
    if (l == 0.0 && r == 0.0) {
        return (!signbit(l) || !signbit(r)) ? 0.0 : -0.0;
    }
    return l >= r ? l : r;
}

#define DFC_NOISE_MEM __constant

inline int dfc_perm(DFC_NOISE_MEM const uchar *permutations, int index) {
    return (int) permutations[index & 255];
}

inline int dfc_perm_512(DFC_NOISE_MEM const uchar *permutations, int index) {
    return (int) permutations[index];
}

inline double dfc_perlin_fade(double value) {
    return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
}

inline double dfc_perlin_grad_from_hash(int hash, double x, double y, double z) {
    int h = hash & 15;
    double u = h < 8 ? x : y;
    double v = h < 4 ? y : ((h == 12 || h == 14) ? x : z);
    return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
}

inline double dfc_lerp3(double dx, double dy, double dz,
                        double x0y0z0, double x1y0z0,
                        double x0y1z0, double x1y1z0,
                        double x0y0z1, double x1y0z1,
                        double x0y1z1, double x1y1z1) {
    double x00 = dfc_lerp(dx, x0y0z0, x1y0z0);
    double x10 = dfc_lerp(dx, x0y1z0, x1y1z0);
    double x01 = dfc_lerp(dx, x0y0z1, x1y0z1);
    double x11 = dfc_lerp(dx, x0y1z1, x1y1z1);
    return dfc_lerp(dz, dfc_lerp(dy, x00, x10), dfc_lerp(dy, x01, x11));
}

inline double dfc_perlin_sample(DFC_NOISE_MEM const uchar *permutations,
                                double origin_x, double origin_y, double origin_z,
                                double x, double y, double z) {
    double input_x = x + origin_x;
    double input_y = y + origin_y;
    double input_z = z + origin_z;

    int grid_x = dfc_java_floor(input_x);
    int grid_y = dfc_java_floor(input_y);
    int grid_z = dfc_java_floor(input_z);

    double delta_x = input_x - (double) grid_x;
    double delta_y = input_y - (double) grid_y;
    double delta_z = input_z - (double) grid_z;

    double x1 = delta_x - 1.0;
    double y1 = delta_y - 1.0;
    double z1 = delta_z - 1.0;

    int ix = grid_x & 255;
    int iy = grid_y & 255;
    int iz = grid_z & 255;
    int px0 = dfc_perm_512(permutations, ix);
    int px1 = dfc_perm_512(permutations, ix + 1);
    int pxy00 = dfc_perm_512(permutations, px0 + iy);
    int pxy10 = dfc_perm_512(permutations, px1 + iy);
    int pxy01 = dfc_perm_512(permutations, px0 + iy + 1);
    int pxy11 = dfc_perm_512(permutations, px1 + iy + 1);

    double n000 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy00 + iz),
            delta_x, delta_y, delta_z);
    double n100 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy10 + iz),
            x1, delta_y, delta_z);
    double n010 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy01 + iz),
            delta_x, y1, delta_z);
    double n110 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy11 + iz),
            x1, y1, delta_z);
    double n001 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy00 + iz + 1),
            delta_x, delta_y, z1);
    double n101 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy10 + iz + 1),
            x1, delta_y, z1);
    double n011 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy01 + iz + 1),
            delta_x, y1, z1);
    double n111 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy11 + iz + 1),
            x1, y1, z1);

    return dfc_lerp3(dfc_perlin_fade(delta_x), dfc_perlin_fade(delta_y), dfc_perlin_fade(delta_z),
            n000, n100, n010, n110, n001, n101, n011, n111);
}

inline double dfc_perlin_sample_5(DFC_NOISE_MEM const uchar *permutations,
                                  double origin_x, double origin_y, double origin_z,
                                  double x, double y, double z,
                                  double y_scale, double y_max) {
    double input_x = x + origin_x;
    double input_y = y + origin_y;
    double input_z = z + origin_z;

    int grid_x = dfc_java_floor(input_x);
    int grid_y = dfc_java_floor(input_y);
    int grid_z = dfc_java_floor(input_z);

    double delta_x = input_x - (double) grid_x;
    double delta_y = input_y - (double) grid_y;
    double delta_z = input_z - (double) grid_z;
    double shifted_delta_y = delta_y;
    if (y_scale != 0.0) {
        double max_shift = y_max >= 0.0 && y_max < delta_y ? y_max : delta_y;
        shifted_delta_y = delta_y - floor(max_shift / y_scale + 1.0E-7) * y_scale;
    }

    double x1 = delta_x - 1.0;
    double y1 = shifted_delta_y - 1.0;
    double z1 = delta_z - 1.0;

    int ix = grid_x & 255;
    int iy = grid_y & 255;
    int iz = grid_z & 255;
    int px0 = dfc_perm_512(permutations, ix);
    int px1 = dfc_perm_512(permutations, ix + 1);
    int pxy00 = dfc_perm_512(permutations, px0 + iy);
    int pxy10 = dfc_perm_512(permutations, px1 + iy);
    int pxy01 = dfc_perm_512(permutations, px0 + iy + 1);
    int pxy11 = dfc_perm_512(permutations, px1 + iy + 1);

    double n000 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy00 + iz),
            delta_x, shifted_delta_y, delta_z);
    double n100 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy10 + iz),
            x1, shifted_delta_y, delta_z);
    double n010 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy01 + iz),
            delta_x, y1, delta_z);
    double n110 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy11 + iz),
            x1, y1, delta_z);
    double n001 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy00 + iz + 1),
            delta_x, shifted_delta_y, z1);
    double n101 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy10 + iz + 1),
            x1, shifted_delta_y, z1);
    double n011 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy01 + iz + 1),
            delta_x, y1, z1);
    double n111 = dfc_perlin_grad_from_hash(dfc_perm_512(permutations, pxy11 + iz + 1),
            x1, y1, z1);

    return dfc_lerp3(dfc_perlin_fade(delta_x), dfc_perlin_fade(delta_y), dfc_perlin_fade(delta_z),
            n000, n100, n010, n110, n001, n101, n011, n111);
}

inline double dfc_noise_octave_sample(DFC_NOISE_MEM const uchar *permutations,
                                      DFC_NOISE_MEM const double *origins,
                                      DFC_NOISE_MEM const double *input_factors,
                                      DFC_NOISE_MEM const double *amp_factors,
                                      int idx, double scaled_x, double scaled_y, double scaled_z) {
    double input_factor = input_factors[idx];
    double x = dfc_wrap_axis(scaled_x * input_factor);
    double y = dfc_wrap_axis(scaled_y * input_factor);
    double z = dfc_wrap_axis(scaled_z * input_factor);
    DFC_NOISE_MEM const uchar *octave_perms = permutations + idx * DFC_PERM_STRIDE;
    int origin_idx = idx * 3;
    return amp_factors[idx] * dfc_perlin_sample(octave_perms,
            origins[origin_idx], origins[origin_idx + 1], origins[origin_idx + 2],
            x, y, z);
}

inline double dfc_noise_branch_sample(DFC_NOISE_MEM const uchar *permutations,
                                      DFC_NOISE_MEM const double *origins,
                                      DFC_NOISE_MEM const double *input_factors,
                                      DFC_NOISE_MEM const double *amp_factors,
                                      DFC_NOISE_MEM const int *branch_octave_offsets,
                                      DFC_NOISE_MEM const int *branch_octave_counts,
                                      int branch_idx,
                                      double coord_scale, double bx, double by, double bz) {
    double sum = 0.0;
    double scaled_x = bx * coord_scale;
    double scaled_y = by * coord_scale;
    double scaled_z = bz * coord_scale;
    int octave_base = branch_octave_offsets[branch_idx];
    int octave_count = branch_octave_counts[branch_idx];
    if (octave_count == 1) {
        return dfc_noise_octave_sample(permutations, origins, input_factors, amp_factors,
                octave_base, scaled_x, scaled_y, scaled_z);
    }
    if (octave_count == 2) {
        return dfc_noise_octave_sample(permutations, origins, input_factors, amp_factors,
                octave_base, scaled_x, scaled_y, scaled_z)
                + dfc_noise_octave_sample(permutations, origins, input_factors, amp_factors,
                octave_base + 1, scaled_x, scaled_y, scaled_z);
    }
    for (int octave = 0; octave < octave_count; octave++) {
        int idx = octave_base + octave;
        sum += dfc_noise_octave_sample(permutations, origins, input_factors, amp_factors,
                idx, scaled_x, scaled_y, scaled_z);
    }
    return sum;
}

inline double dfc_noise_slot_sample(DFC_NOISE_MEM const uchar *permutations,
                                    DFC_NOISE_MEM const double *origins,
                                    DFC_NOISE_MEM const double *input_factors,
                                    DFC_NOISE_MEM const double *amp_factors,
                                    DFC_NOISE_MEM const int *branch_octave_offsets,
                                    DFC_NOISE_MEM const int *branch_octave_counts,
                                    DFC_NOISE_MEM const double *branch_coord_scales,
                                    DFC_NOISE_MEM const double *slot_value_factors,
                                    int slot, int branches_per_slot,
                                    double bx, double by, double bz) {
    double value = 0.0;
    int branch_base = slot * branches_per_slot;
    if (branches_per_slot == 2) {
        value = dfc_noise_branch_sample(permutations, origins, input_factors, amp_factors,
                branch_octave_offsets, branch_octave_counts, branch_base,
                branch_coord_scales[branch_base], bx, by, bz)
                + dfc_noise_branch_sample(permutations, origins, input_factors, amp_factors,
                branch_octave_offsets, branch_octave_counts, branch_base + 1,
                branch_coord_scales[branch_base + 1], bx, by, bz);
        return value * slot_value_factors[slot];
    }
    for (int branch = 0; branch < branches_per_slot; branch++) {
        int branch_idx = branch_base + branch;
        value += dfc_noise_branch_sample(permutations, origins, input_factors, amp_factors,
                branch_octave_offsets, branch_octave_counts, branch_idx,
                branch_coord_scales[branch_idx], bx, by, bz);
    }
    return value * slot_value_factors[slot];
}

#define DFC_CELL_GRID_LAYOUT_XZ 0
#define DFC_CELL_GRID_LAYOUT_Y_COLUMN 1
#define DFC_CELL_GRID_LAYOUT_Y_Z_SLICE 2
#define DFC_CELL_GRID_LAYOUT_KIND_MASK 255
#define DFC_CELL_GRID_LAYOUT_STRIDE_SHIFT 8

inline int dfc_cell_grid_coords(int gid, int first_block_x, int first_block_y, int first_block_z,
                                int cell_w, int cell_h, int cells, int layout,
                                __private double *bx, __private double *by, __private double *bz,
                                __private int *cell_out) {
    if (gid < 0 || cell_w <= 0 || cell_h <= 0 || cells <= 0) {
        return 0;
    }

    int cell;
    int y_index;
    int ix;
    int iz;
    if (cell_w == 4 && cell_h == 8) {
        cell = gid >> 7;
        int in_cell = gid & 127;
        y_index = in_cell >> 4;
        int plane = in_cell & 15;
        ix = plane >> 2;
        iz = plane & 3;
    } else {
        int plane_size = cell_w * cell_w;
        int cell_volume = plane_size * cell_h;
        if (cell_volume <= 0) {
            return 0;
        }
        cell = gid / cell_volume;
        int in_cell = gid - cell * cell_volume;
        y_index = in_cell / plane_size;
        int plane = in_cell - y_index * plane_size;
        ix = plane / cell_w;
        iz = plane - ix * cell_w;
    }

    int layout_kind = layout & DFC_CELL_GRID_LAYOUT_KIND_MASK;
    if (layout_kind == DFC_CELL_GRID_LAYOUT_Y_COLUMN) {
        *bx = (double) (first_block_x + ix);
        *by = (double) (first_block_y + cell * cell_h + (cell_h - 1 - y_index));
        *bz = (double) (first_block_z + iz);
    } else if (layout_kind == DFC_CELL_GRID_LAYOUT_Y_Z_SLICE) {
        int stride = layout >> DFC_CELL_GRID_LAYOUT_STRIDE_SHIFT;
        if (stride <= 0) {
            return 0;
        }
        int cell_y = cell - (cell / stride) * stride;
        int cell_z = cell / stride;
        *bx = (double) (first_block_x + ix);
        *by = (double) (first_block_y + cell_y * cell_h + (cell_h - 1 - y_index));
        *bz = (double) (first_block_z + cell_z * cell_w + iz);
    } else {
        int cell_x = cell & 31;
        int cell_z = cell >> 5;
        *bx = (double) (first_block_x + cell_x * cell_w + ix);
        *by = (double) (first_block_y + (cell_h - 1 - y_index));
        *bz = (double) (first_block_z + cell_z * cell_w + iz);
    }
    *cell_out = cell;
    return 1;
}

inline int dfc_push(__private double *stk, __private int *sp, double value) {
    if (*sp >= DFC_SLAB_STACK) {
        return 0;
    }
    stk[(*sp)++] = value;
    return 1;
}

inline int dfc_pop(__private double *stk, __private int *sp, __private double *out) {
    if (*sp <= 0) {
        return 0;
    }
    *out = stk[--(*sp)];
    return 1;
}

inline int dfc_slab_vm_eval_one(__global const uchar *bc, int bc_len,
                                __global const double *consts, int nconst,
                                __global const double *slot_rows_flat,
                                int n_slots, int slot_row_stride,
                                double bx, double by, double bz,
                                double y_hoist, int flat_idx,
                                __private double *result) {
    double stk[DFC_SLAB_STACK];
    int sp = 0;
    for (int pc = 0; pc < bc_len;) {
        int op = (int) bc[pc++];
        switch (op) {
            case DFC_OP_PUSH_CONST: {
                if (pc + 2 > bc_len) return 0;
                int idx = (int) dfc_read_u16(bc, pc);
                pc += 2;
                if (idx < 0 || idx >= nconst) return 0;
                if (!dfc_push(stk, &sp, consts[idx])) return 0;
                break;
            }
            case DFC_OP_PUSH_SLOT: {
                if (pc >= bc_len) return 0;
                int slot = (int) bc[pc++];
                if (slot < 0 || slot >= n_slots || slot_row_stride <= 0) return 0;
                if (!dfc_push(stk, &sp, slot_rows_flat[slot * slot_row_stride + flat_idx])) return 0;
                break;
            }
            case DFC_OP_COND_NEG_SCALE: {
                if (pc + 2 > bc_len) return 0;
                int idx = (int) dfc_read_u16(bc, pc);
                pc += 2;
                if (idx < 0 || idx >= nconst) return 0;
                double x;
                if (!dfc_pop(stk, &sp, &x)) return 0;
                if (!dfc_push(stk, &sp, x > 0.0 ? x : x * consts[idx])) return 0;
                break;
            }
            case DFC_OP_Y_CLAMPED_GRADIENT: {
                if (pc + 8 > bc_len) return 0;
                int from_y_idx = (int) dfc_read_u16(bc, pc); pc += 2;
                int to_y_idx = (int) dfc_read_u16(bc, pc); pc += 2;
                int from_value_idx = (int) dfc_read_u16(bc, pc); pc += 2;
                int to_value_idx = (int) dfc_read_u16(bc, pc); pc += 2;
                if (from_y_idx < 0 || from_y_idx >= nconst || to_y_idx < 0 || to_y_idx >= nconst
                        || from_value_idx < 0 || from_value_idx >= nconst
                        || to_value_idx < 0 || to_value_idx >= nconst) return 0;
                if (!dfc_push(stk, &sp, dfc_clamped_map(by, consts[from_y_idx], consts[to_y_idx],
                        consts[from_value_idx], consts[to_value_idx]))) return 0;
                break;
            }
            case DFC_OP_RANGE_CHOICE: {
                if (pc + 4 > bc_len || sp < 3) return 0;
                int min_idx = (int) dfc_read_u16(bc, pc); pc += 2;
                int max_idx = (int) dfc_read_u16(bc, pc); pc += 2;
                if (min_idx < 0 || min_idx >= nconst || max_idx < 0 || max_idx >= nconst) return 0;
                double when_out, when_in, input;
                if (!dfc_pop(stk, &sp, &when_out)) return 0;
                if (!dfc_pop(stk, &sp, &when_in)) return 0;
                if (!dfc_pop(stk, &sp, &input)) return 0;
                if (!dfc_push(stk, &sp, input >= consts[min_idx] && input < consts[max_idx] ? when_in : when_out)) return 0;
                break;
            }
            case DFC_OP_RANGE_CHOICE_JUMP: {
                if (pc + 12 > bc_len || sp < 1) return 0;
                int min_idx = (int) dfc_read_u16(bc, pc); pc += 2;
                int max_idx = (int) dfc_read_u16(bc, pc); pc += 2;
                int when_in_pc = dfc_read_i32(bc, pc); pc += 4;
                int when_out_pc = dfc_read_i32(bc, pc); pc += 4;
                if (min_idx < 0 || min_idx >= nconst || max_idx < 0 || max_idx >= nconst
                        || when_in_pc < 0 || when_in_pc >= bc_len
                        || when_out_pc < 0 || when_out_pc >= bc_len) return 0;
                double input;
                if (!dfc_pop(stk, &sp, &input)) return 0;
                pc = input >= consts[min_idx] && input < consts[max_idx] ? when_in_pc : when_out_pc;
                break;
            }
            case DFC_OP_JUMP: {
                if (pc + 4 > bc_len) return 0;
                int jump_pc = dfc_read_i32(bc, pc);
                if (jump_pc < 0 || jump_pc > bc_len) return 0;
                pc = jump_pc;
                break;
            }
            case DFC_OP_BLOCK_X:
                if (!dfc_push(stk, &sp, bx)) return 0;
                break;
            case DFC_OP_BLOCK_Y:
                if (!dfc_push(stk, &sp, by)) return 0;
                break;
            case DFC_OP_BLOCK_Z:
                if (!dfc_push(stk, &sp, bz)) return 0;
                break;
            case DFC_OP_HOIST:
                if (!dfc_push(stk, &sp, y_hoist)) return 0;
                break;
            case DFC_OP_ADD:
            case DFC_OP_SUB:
            case DFC_OP_MUL:
            case DFC_OP_DIV:
            case DFC_OP_MIN:
            case DFC_OP_MAX: {
                double r, l, v;
                if (!dfc_pop(stk, &sp, &r)) return 0;
                if (!dfc_pop(stk, &sp, &l)) return 0;
                if (op == DFC_OP_ADD) v = l + r;
                else if (op == DFC_OP_SUB) v = l - r;
                else if (op == DFC_OP_MUL) v = l * r;
                else if (op == DFC_OP_DIV) v = l / r;
                else if (op == DFC_OP_MIN) v = dfc_java_min(l, r);
                else v = dfc_java_max(l, r);
                if (!dfc_push(stk, &sp, v)) return 0;
                break;
            }
            case DFC_OP_NEG:
            case DFC_OP_ABS:
            case DFC_OP_SQUARE:
            case DFC_OP_SQUEEZE: {
                double x, v;
                if (!dfc_pop(stk, &sp, &x)) return 0;
                if (op == DFC_OP_NEG) v = -x;
                else if (op == DFC_OP_ABS) v = fabs(x);
                else if (op == DFC_OP_SQUARE) v = x * x;
                else v = dfc_squeeze(x);
                if (!dfc_push(stk, &sp, v)) return 0;
                break;
            }
            default:
                return 0;
        }
    }
    if (sp != 1) {
        return 0;
    }
    *result = stk[0];
    return 1;
}

__kernel void dfc_slab_vm_eval(__global const uchar *bc, int bc_len,
                               __global const double *consts, int nconst,
                               __global const double *slot_rows_flat,
                               int n_slots, int slot_row_stride,
                               int cell_start_x, int cell_start_z,
                               int block_y, int cell_w,
                               int slab_layout, int col_xi, int col_zi,
                               int cell_height, double y_hoist,
                               __global double *out, int n) {
    int gid = (int) get_global_id(0);
    if (gid >= n || cell_w <= 0) {
        return;
    }

    double bx;
    double by;
    double bz;
    if (slab_layout == DFC_SLAB_LAYOUT_Y_HOIST) {
        int ix = gid / cell_w;
        int iz = gid - ix * cell_w;
        bx = (double) (cell_start_x + ix);
        by = (double) block_y;
        bz = (double) (cell_start_z + iz);
    } else {
        if (cell_height <= 0) {
            return;
        }
        bx = (double) (cell_start_x + col_xi);
        by = (double) (block_y + (cell_height - 1 - gid));
        bz = (double) (cell_start_z + col_zi);
    }

    double value;
    int ok = dfc_slab_vm_eval_one(bc, bc_len, consts, nconst, slot_rows_flat,
            n_slots, slot_row_stride, bx, by, bz, y_hoist, gid, &value);
    out[gid] = ok ? value : 0.0;
}

__kernel void dfc_slab_vm_eval_coords(__global const uchar *bc, int bc_len,
                                      __global const double *consts, int nconst,
                                      __global const double *slot_rows_flat,
                                      int n_slots, int slot_row_stride,
                                      __global const double *block_x,
                                      __global const double *block_y,
                                      __global const double *block_z,
                                      __global const double *hoist,
                                      __global double *out, int n) {
    int gid = (int) get_global_id(0);
    if (gid >= n) {
        return;
    }

    double value;
    int ok = dfc_slab_vm_eval_one(bc, bc_len, consts, nconst, slot_rows_flat,
            n_slots, slot_row_stride, block_x[gid], block_y[gid], block_z[gid], hoist[gid], gid, &value);
    out[gid] = ok ? value : 0.0;
}

__kernel void dfc_slab_vm_eval_cell_grid(__global const uchar *bc, int bc_len,
                                         __global const double *consts, int nconst,
                                         __global const double *slot_rows_flat,
                                         int n_slots, int slot_row_stride,
                                         int first_block_x, int first_block_y, int first_block_z,
                                         int cell_w, int cell_h, int cells, int layout, double hoist_base,
                                         __global double *out, int n) {
    int gid = (int) get_global_id(0);
    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) {
        return;
    }

    double bx;
    double by;
    double bz;
    int cell;
    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z,
            cell_w, cell_h, cells, layout, &bx, &by, &bz, &cell)) {
        return;
    }
    double y_hoist = hoist_base + (double) (cell & 7) * 0.03125;

    double value;
    int ok = dfc_slab_vm_eval_one(bc, bc_len, consts, nconst, slot_rows_flat,
            n_slots, slot_row_stride, bx, by, bz, y_hoist, gid, &value);
    out[gid] = ok ? value : 0.0;
}

__kernel void dfc_slab_vm_eval_cell_grid_slot_buffer(__global const uchar *bc, int bc_len,
                                                     __global const double *consts, int nconst,
                                                     __global double *slot_rows_flat,
                                                     int n_slots, int slot_row_stride,
                                                     int first_block_x, int first_block_y, int first_block_z,
                                                     int cell_w, int cell_h, int cells, int layout, double hoist_base,
                                                     int target_slot, int n) {
    int gid = (int) get_global_id(0);
    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0
            || target_slot < 0 || target_slot >= n_slots || slot_row_stride <= 0) {
        return;
    }

    double bx;
    double by;
    double bz;
    int cell;
    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z,
            cell_w, cell_h, cells, layout, &bx, &by, &bz, &cell)) {
        return;
    }
    double y_hoist = hoist_base + (double) (cell & 7) * 0.03125;

    double value;
    int ok = dfc_slab_vm_eval_one(bc, bc_len, consts, nconst, slot_rows_flat,
            n_slots, slot_row_stride, bx, by, bz, y_hoist, gid, &value);
    slot_rows_flat[target_slot * slot_row_stride + gid] = ok ? value : 0.0;
}

__kernel void dfc_slab_vm_fill_demo_slots(__global double *slot_rows_flat, int n) {
    int gid = (int) get_global_id(0);
    if (gid >= n) {
        return;
    }
    slot_rows_flat[gid] = (double) (gid & 63) * 0.5;
    slot_rows_flat[n + gid] = 10.0 - (double) (gid & 31) * 0.25;
}

__kernel void dfc_slab_vm_fill_noise_slots(__global double *slot_rows_flat, int n,
                                           DFC_NOISE_MEM const uchar *permutations,
                                           DFC_NOISE_MEM const double *origins,
                                           DFC_NOISE_MEM const double *input_factors,
                                           DFC_NOISE_MEM const double *amp_factors,
                                           DFC_NOISE_MEM const int *branch_octave_offsets,
                                           DFC_NOISE_MEM const int *branch_octave_counts,
                                           DFC_NOISE_MEM const double *branch_coord_scales,
                                           DFC_NOISE_MEM const double *slot_value_factors,
                                           int slot_count, int branches_per_slot,
                                           int octaves_per_branch,
                                           int first_block_x, int first_block_y, int first_block_z,
                                           int cell_w, int cell_h, int cells, int layout) {
    int gid = (int) get_global_id(0);
    if (gid >= n || slot_count <= 0 || branches_per_slot <= 0
            || octaves_per_branch <= 0 || cell_w <= 0 || cell_h <= 0 || cells <= 0) {
        return;
    }

    double bx;
    double by;
    double bz;
    int cell;
    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z,
            cell_w, cell_h, cells, layout, &bx, &by, &bz, &cell)) {
        return;
    }

    for (int slot = 0; slot < slot_count; slot++) {
        slot_rows_flat[slot * n + gid] = dfc_noise_slot_sample(permutations, origins, input_factors, amp_factors,
                branch_octave_offsets, branch_octave_counts, branch_coord_scales, slot_value_factors,
                slot, branches_per_slot, bx, by, bz);
    }
}

__kernel void dfc_slab_vm_fill_noise_slots_by_slot(__global double *slot_rows_flat, int n,
                                                   DFC_NOISE_MEM const uchar *permutations,
                                                   DFC_NOISE_MEM const double *origins,
                                                   DFC_NOISE_MEM const double *input_factors,
                                                   DFC_NOISE_MEM const double *amp_factors,
                                                   DFC_NOISE_MEM const int *branch_octave_offsets,
                                                   DFC_NOISE_MEM const int *branch_octave_counts,
                                                   DFC_NOISE_MEM const double *branch_coord_scales,
                                                   DFC_NOISE_MEM const double *slot_value_factors,
                                                   int slot_count, int branches_per_slot,
                                                   int octaves_per_branch,
                                                   int first_block_x, int first_block_y, int first_block_z,
                                                   int cell_w, int cell_h, int cells, int layout) {
    int flat = (int) get_global_id(0);
    int total = n * slot_count;
    if (flat >= total || n <= 0 || slot_count <= 0 || branches_per_slot <= 0
            || octaves_per_branch <= 0 || cell_w <= 0 || cell_h <= 0 || cells <= 0) {
        return;
    }

    int slot = flat / n;
    int gid = flat - slot * n;
    double bx;
    double by;
    double bz;
    int cell;
    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z,
            cell_w, cell_h, cells, layout, &bx, &by, &bz, &cell)) {
        return;
    }

    slot_rows_flat[flat] = dfc_noise_slot_sample(permutations, origins, input_factors, amp_factors,
            branch_octave_offsets, branch_octave_counts, branch_coord_scales, slot_value_factors,
            slot, branches_per_slot, bx, by, bz);
}

__kernel void dfc_slab_vm_eval_cell_grid_direct_noise(DFC_NOISE_MEM const uchar *permutations,
                                                      DFC_NOISE_MEM const double *origins,
                                                      DFC_NOISE_MEM const double *input_factors,
                                                      DFC_NOISE_MEM const double *amp_factors,
                                                      DFC_NOISE_MEM const int *branch_octave_offsets,
                                                      DFC_NOISE_MEM const int *branch_octave_counts,
                                                      DFC_NOISE_MEM const double *branch_coord_scales,
                                                      DFC_NOISE_MEM const double *slot_value_factors,
                                                      int slot_count, int branches_per_slot,
                                                      int octaves_per_branch, int used_slot_count,
                                                      int first_block_x, int first_block_y, int first_block_z,
                                                      int cell_w, int cell_h, int cells, int layout, double hoist_base,
                                                      __global double *out, int n) {
    int gid = (int) get_global_id(0);
    if (gid >= n || slot_count <= 0 || branches_per_slot <= 0 || used_slot_count <= 0
            || octaves_per_branch <= 0 || cell_w <= 0 || cell_h <= 0 || cells <= 0) {
        return;
    }

    double bx;
    double by;
    double bz;
    int cell;
    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z,
            cell_w, cell_h, cells, layout, &bx, &by, &bz, &cell)) {
        return;
    }
    int slots_to_use = used_slot_count < slot_count ? used_slot_count : slot_count;

    double value = 0.0;
    for (int slot = 0; slot < slots_to_use; slot++) {
        value += dfc_noise_slot_sample(permutations, origins, input_factors, amp_factors,
                branch_octave_offsets, branch_octave_counts, branch_coord_scales, slot_value_factors,
                slot, branches_per_slot, bx, by, bz);
    }
    double y_hoist = hoist_base + (double) (cell & 7) * 0.03125;
    out[gid] = value + y_hoist + bx - bz + dfc_squeeze(by * 0.1);
}

__kernel void dfc_slab_vm_eval_cell_grid_direct_demo(int first_block_x, int first_block_y, int first_block_z,
                                                     int cell_w, int cell_h, int cells, int layout, double hoist_base,
                                                     __global double *out, int n) {
    int gid = (int) get_global_id(0);
    if (gid >= n || cell_w <= 0 || cell_h <= 0 || cells <= 0) {
        return;
    }

    double bx;
    double by;
    double bz;
    int cell;
    if (!dfc_cell_grid_coords(gid, first_block_x, first_block_y, first_block_z,
            cell_w, cell_h, cells, layout, &bx, &by, &bz, &cell)) {
        return;
    }

    double slot0 = (double) (gid & 63) * 0.5;
    double slot1 = 10.0 - (double) (gid & 31) * 0.25;
    double y_hoist = hoist_base + (double) (cell & 7) * 0.03125;
    out[gid] = slot0 + slot1 + y_hoist + bx - bz + dfc_squeeze(by * 0.1);
}

#endif // DFC_OPENCL_SLAB_VM_CL
