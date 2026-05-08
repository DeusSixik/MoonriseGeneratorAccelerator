#include "dfc_internal.h"
#include <math.h>
#include <stdlib.h>
#include <stdint.h>

/* Postfix slab program opcodes — must match SlabInnerNativeProgram in Java. */
enum {
  OP_PUSH_CONST = 1,
  OP_PUSH_SLOT = 2,
  OP_COND_NEG_SCALE = 3,
  OP_Y_CLAMPED_GRADIENT = 4,
  OP_RANGE_CHOICE = 5,
  OP_BLOCK_X = 16,
  OP_BLOCK_Y = 17,
  OP_BLOCK_Z = 18,
  OP_HOIST = 19,
  OP_ADD = 32,
  OP_SUB = 33,
  OP_MUL = 34,
  OP_DIV = 35,
  OP_MIN = 36,
  OP_MAX = 37,
  OP_NEG = 48,
  OP_ABS = 49,
  OP_SQUARE = 50,
  OP_SQUEEZE = 51,
};

#define DFC_SLAB_STACK 192

typedef struct {
  uint8_t op;
  uint8_t slot;
  double d0;
  double d1;
  double d2;
  double d3;
} DfcDecodedOp;

static _Thread_local DfcDecodedOp *dfc_slab_decoded_tls = NULL;
static _Thread_local int dfc_slab_decoded_tls_cap = 0;

static inline int read_u16_le(const uint8_t *bc, int pc) {
  return (int) bc[pc] | ((int) bc[pc + 1] << 8);
}

static inline void push(double *stk, int *sp, double v) {
  if (*sp >= DFC_SLAB_STACK) {
    *sp = DFC_SLAB_STACK + 1;
    return;
  }
  stk[(*sp)++] = v;
}

static inline double pop(double *stk, int *sp) {
  if (*sp <= 0) return 0.0;
  return stk[--(*sp)];
}

static inline double java_math_min(double l, double r) {
  if (isnan(l) || isnan(r)) return NAN;
  if (l == 0.0 && r == 0.0) {
    return (signbit(l) || signbit(r)) ? -0.0 : 0.0;
  }
  return l <= r ? l : r;
}

static inline double java_math_max(double l, double r) {
  if (isnan(l) || isnan(r)) return NAN;
  if (l == 0.0 && r == 0.0) {
    return (!signbit(l) || !signbit(r)) ? 0.0 : -0.0;
  }
  return l >= r ? l : r;
}

static inline double java_squeeze(double x) {
  /* Mirror Runtime.squeeze's compare-based clamp; fmin/fmax handle NaN differently. */
  double c = x < -1.0 ? -1.0 : (x > 1.0 ? 1.0 : x);
  return c / 2.0 - c * c * c / 24.0;
}

static inline double java_clamped_map(double value, double old_min, double old_max,
                                      double new_min, double new_max) {
  double t = (value - old_min) / (old_max - old_min);
  if (t < 0.0) {
    t = 0.0;
  } else if (t > 1.0) {
    t = 1.0;
  }
  return new_min + t * (new_max - new_min);
}

static DfcDecodedOp *ensure_decoded_scratch(int need) {
  if (need <= 0) {
    return NULL;
  }
  if (dfc_slab_decoded_tls_cap >= need && dfc_slab_decoded_tls) {
    return dfc_slab_decoded_tls;
  }
  DfcDecodedOp *grown = (DfcDecodedOp *) realloc(dfc_slab_decoded_tls, (size_t) need * sizeof(DfcDecodedOp));
  if (!grown) {
    return NULL;
  }
  dfc_slab_decoded_tls = grown;
  dfc_slab_decoded_tls_cap = need;
  return grown;
}

static int decode_program(const uint8_t *bc, int bc_len, const double *consts, int nconst,
                          int n_slots, DfcDecodedOp *ops, int *out_count) {
  int pc = 0;
  int oc = 0;
  while (pc < bc_len) {
    uint8_t op = bc[pc++];
    DfcDecodedOp ins;
    ins.op = op;
    ins.slot = 0;
    ins.d0 = 0.0;
    ins.d1 = 0.0;
    ins.d2 = 0.0;
    ins.d3 = 0.0;
    switch (op) {
      case OP_PUSH_CONST: {
        if (pc + 2 > bc_len) return 0;
        int idx = read_u16_le(bc, pc);
        pc += 2;
        if (idx < 0 || idx >= nconst) return 0;
        ins.d0 = consts[idx];
        break;
      }
      case OP_PUSH_SLOT: {
        if (pc >= bc_len) return 0;
        int s = (int) bc[pc++];
        if (s < 0 || s >= n_slots) return 0;
        ins.slot = (uint8_t) s;
        break;
      }
      case OP_COND_NEG_SCALE: {
        if (pc + 2 > bc_len) return 0;
        int idx = read_u16_le(bc, pc);
        pc += 2;
        if (idx < 0 || idx >= nconst) return 0;
        ins.d0 = consts[idx];
        break;
      }
      case OP_Y_CLAMPED_GRADIENT: {
        if (pc + 8 > bc_len) return 0;
        int from_y_idx = read_u16_le(bc, pc);
        pc += 2;
        int to_y_idx = read_u16_le(bc, pc);
        pc += 2;
        int from_value_idx = read_u16_le(bc, pc);
        pc += 2;
        int to_value_idx = read_u16_le(bc, pc);
        pc += 2;
        if (from_y_idx < 0 || from_y_idx >= nconst
            || to_y_idx < 0 || to_y_idx >= nconst
            || from_value_idx < 0 || from_value_idx >= nconst
            || to_value_idx < 0 || to_value_idx >= nconst) {
          return 0;
        }
        ins.d0 = consts[from_y_idx];
        ins.d1 = consts[to_y_idx];
        ins.d2 = consts[from_value_idx];
        ins.d3 = consts[to_value_idx];
        break;
      }
      case OP_RANGE_CHOICE: {
        if (pc + 4 > bc_len) return 0;
        int min_idx = read_u16_le(bc, pc);
        pc += 2;
        int max_idx = read_u16_le(bc, pc);
        pc += 2;
        if (min_idx < 0 || min_idx >= nconst || max_idx < 0 || max_idx >= nconst) return 0;
        ins.d0 = consts[min_idx];
        ins.d1 = consts[max_idx];
        break;
      }
      case OP_BLOCK_X:
      case OP_BLOCK_Y:
      case OP_BLOCK_Z:
      case OP_HOIST:
      case OP_ADD:
      case OP_SUB:
      case OP_MUL:
      case OP_DIV:
      case OP_MIN:
      case OP_MAX:
      case OP_NEG:
      case OP_ABS:
      case OP_SQUARE:
      case OP_SQUEEZE:
        break;
      default:
        return 0;
    }
    ops[oc++] = ins;
  }
  *out_count = oc;
  return 1;
}

/** @return 1 on success (single value on stack), 0 on error */
static int eval_at_coords(const uint8_t *bc, int bc_len, const double *consts, int nconst, const double *slot_rows_flat,
                          int n_slots, int slot_row_stride, double bx, double by, double bz,
                          double y_hoist, int flat_idx, double *result) {
  double stk[DFC_SLAB_STACK];
  int sp = 0;
  for (int pc = 0; pc < bc_len;) {
    uint8_t op = bc[pc++];
    switch (op) {
      case OP_PUSH_CONST: {
        if (pc + 2 > bc_len) return 0;
        int idx = read_u16_le(bc, pc);
        pc += 2;
        if (idx < 0 || idx >= nconst) return 0;
        push(stk, &sp, consts[idx]);
        break;
      }
      case OP_PUSH_SLOT: {
        if (pc >= bc_len) return 0;
        int s = (int) bc[pc++];
        if (s < 0 || s >= n_slots || !slot_rows_flat || slot_row_stride <= 0) return 0;
        push(stk, &sp, slot_rows_flat[(size_t) s * (size_t) slot_row_stride + (size_t) flat_idx]);
        break;
      }
      case OP_COND_NEG_SCALE: {
        if (pc + 2 > bc_len) return 0;
        int idx = read_u16_le(bc, pc);
        pc += 2;
        if (idx < 0 || idx >= nconst) return 0;
        if (sp < 1) return 0;
        double f = consts[idx];
        double x = pop(stk, &sp);
        push(stk, &sp, x > 0.0 ? x : x * f);
        break;
      }
      case OP_Y_CLAMPED_GRADIENT: {
        if (pc + 8 > bc_len) return 0;
        int from_y_idx = read_u16_le(bc, pc);
        pc += 2;
        int to_y_idx = read_u16_le(bc, pc);
        pc += 2;
        int from_value_idx = read_u16_le(bc, pc);
        pc += 2;
        int to_value_idx = read_u16_le(bc, pc);
        pc += 2;
        if (from_y_idx < 0 || from_y_idx >= nconst
            || to_y_idx < 0 || to_y_idx >= nconst
            || from_value_idx < 0 || from_value_idx >= nconst
            || to_value_idx < 0 || to_value_idx >= nconst) {
          return 0;
        }
        push(stk, &sp, java_clamped_map(by, consts[from_y_idx], consts[to_y_idx],
                                        consts[from_value_idx], consts[to_value_idx]));
        break;
      }
      case OP_RANGE_CHOICE: {
        if (pc + 4 > bc_len) return 0;
        int min_idx = read_u16_le(bc, pc);
        pc += 2;
        int max_idx = read_u16_le(bc, pc);
        pc += 2;
        if (min_idx < 0 || min_idx >= nconst || max_idx < 0 || max_idx >= nconst) return 0;
        if (sp < 3) return 0;
        double when_out = pop(stk, &sp);
        double when_in = pop(stk, &sp);
        double input = pop(stk, &sp);
        double min_inclusive = consts[min_idx];
        double max_exclusive = consts[max_idx];
        push(stk, &sp, (input >= min_inclusive && input < max_exclusive) ? when_in : when_out);
        break;
      }
      case OP_BLOCK_X:
        push(stk, &sp, bx);
        break;
      case OP_BLOCK_Y:
        push(stk, &sp, by);
        break;
      case OP_BLOCK_Z:
        push(stk, &sp, bz);
        break;
      case OP_HOIST:
        push(stk, &sp, y_hoist);
        break;
      case OP_ADD: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, l + r);
        break;
      }
      case OP_SUB: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, l - r);
        break;
      }
      case OP_MUL: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, l * r);
        break;
      }
      case OP_DIV: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, l / r);
        break;
      }
      case OP_MIN: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, java_math_min(l, r));
        break;
      }
      case OP_MAX: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, java_math_max(l, r));
        break;
      }
      case OP_NEG: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, -x);
        break;
      }
      case OP_ABS: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, fabs(x));
        break;
      }
      case OP_SQUARE: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, x * x);
        break;
      }
      case OP_SQUEEZE: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, java_squeeze(x));
        break;
      }
      default:
        return 0;
    }
  }
  if (sp != 1) return 0;
  *result = stk[0];
  return 1;
}

/** @return 1 on success (single value on stack), 0 on error */
static int eval_at_coords_decoded(const DfcDecodedOp *ops, int op_count, const double *slot_rows_flat,
                                  int n_slots, int slot_row_stride, double bx, double by, double bz,
                                  double y_hoist, int flat_idx, double *result) {
  double stk[DFC_SLAB_STACK];
  int sp = 0;
  for (int pc = 0; pc < op_count; pc++) {
    const DfcDecodedOp *ins = ops + pc;
    switch (ins->op) {
      case OP_PUSH_CONST:
        push(stk, &sp, ins->d0);
        break;
      case OP_PUSH_SLOT: {
        push(stk, &sp, slot_rows_flat[(size_t) ins->slot * (size_t) slot_row_stride + (size_t) flat_idx]);
        break;
      }
      case OP_COND_NEG_SCALE: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, x > 0.0 ? x : x * ins->d0);
        break;
      }
      case OP_Y_CLAMPED_GRADIENT:
        push(stk, &sp, java_clamped_map(by, ins->d0, ins->d1, ins->d2, ins->d3));
        break;
      case OP_RANGE_CHOICE: {
        if (sp < 3) return 0;
        double when_out = pop(stk, &sp);
        double when_in = pop(stk, &sp);
        double input = pop(stk, &sp);
        push(stk, &sp, (input >= ins->d0 && input < ins->d1) ? when_in : when_out);
        break;
      }
      case OP_BLOCK_X:
        push(stk, &sp, bx);
        break;
      case OP_BLOCK_Y:
        push(stk, &sp, by);
        break;
      case OP_BLOCK_Z:
        push(stk, &sp, bz);
        break;
      case OP_HOIST:
        push(stk, &sp, y_hoist);
        break;
      case OP_ADD: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, l + r);
        break;
      }
      case OP_SUB: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, l - r);
        break;
      }
      case OP_MUL: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, l * r);
        break;
      }
      case OP_DIV: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, l / r);
        break;
      }
      case OP_MIN: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, java_math_min(l, r));
        break;
      }
      case OP_MAX: {
        if (sp < 2) return 0;
        double r = pop(stk, &sp);
        double l = pop(stk, &sp);
        push(stk, &sp, java_math_max(l, r));
        break;
      }
      case OP_NEG: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, -x);
        break;
      }
      case OP_ABS: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, fabs(x));
        break;
      }
      case OP_SQUARE: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, x * x);
        break;
      }
      case OP_SQUEEZE: {
        if (sp < 1) return 0;
        double x = pop(stk, &sp);
        push(stk, &sp, java_squeeze(x));
        break;
      }
      default:
        return 0;
    }
  }
  if (sp != 1) return 0;
  *result = stk[0];
  return 1;
}

void dfc_slab_inner_eval_batch(const uint8_t *bc, int bc_len, const double *consts, int nconst,
                               const double *slot_rows_flat, int n_slots, int slot_row_stride,
                               int cell_start_x, int cell_start_z,
                               int block_y, int cell_w, double y_hoist, int slab_layout, int col_xi, int col_zi,
                               int cell_height, double *out, int n) {
  if (!bc || bc_len <= 0 || !out || n <= 0 || cell_w <= 0) return;
  if (n_slots > 0 && (!slot_rows_flat || slot_row_stride <= 0)) return;
  DfcDecodedOp *ops = ensure_decoded_scratch(bc_len);
  int op_count = 0;
  if (ops) {
    if (!decode_program(bc, bc_len, consts, nconst, n_slots, ops, &op_count)) {
      ops = NULL;
    }
  }
  if (slab_layout == 0) {
    const double by = (double) block_y;
    int flat = 0;
    for (int ix = 0; ix < cell_w && flat < n; ix++) {
      const double bx = (double) (cell_start_x + ix);
      for (int iz = 0; iz < cell_w && flat < n; iz++, flat++) {
        const double bz = (double) (cell_start_z + iz);
        double v;
        int ok = ops
                ? eval_at_coords_decoded(ops, op_count, slot_rows_flat, n_slots, slot_row_stride, bx, by, bz, y_hoist, flat, &v)
                : eval_at_coords(bc, bc_len, consts, nconst, slot_rows_flat, n_slots, slot_row_stride,
                                 bx, by, bz, y_hoist, flat, &v);
        if (!ok) {
          out[flat] = 0.0;
        } else {
          out[flat] = v;
        }
      }
    }
    return;
  }

  /* XZ-hoist column: fixed (xi, zi), flat index walks Y top -> bottom. */
  if (cell_height <= 0) return;
  {
    const double bx = (double) (cell_start_x + col_xi);
    const double bz = (double) (cell_start_z + col_zi);
    for (int i = 0; i < n; i++) {
      const double by = (double) (block_y + (cell_height - 1 - i));
      double v;
      int ok = ops
              ? eval_at_coords_decoded(ops, op_count, slot_rows_flat, n_slots, slot_row_stride, bx, by, bz, y_hoist, i, &v)
              : eval_at_coords(bc, bc_len, consts, nconst, slot_rows_flat, n_slots, slot_row_stride,
                               bx, by, bz, y_hoist, i, &v);
      if (!ok) {
        out[i] = 0.0;
      } else {
        out[i] = v;
      }
    }
  }
}
