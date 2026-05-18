#ifndef DFC_OPENCL_SLAB_VM_CL
#define DFC_OPENCL_SLAB_VM_CL

#define DFC_OP_PUSH_CONST 1
#define DFC_OP_PUSH_SLOT 2
#define DFC_OP_COND_NEG_SCALE 3
#define DFC_OP_Y_CLAMPED_GRADIENT 4
#define DFC_OP_RANGE_CHOICE 5
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

inline ushort dfc_read_u16(__global const uchar *bc, int pc) {
    return (ushort) bc[pc] | (ushort) ((ushort) bc[pc + 1] << 8);
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

#endif // DFC_OPENCL_SLAB_VM_CL