#include "dfc_internal.h"
#include <math.h>
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

/** @return 1 on success (single value on stack), 0 on error */
static int eval_at_flat(const uint8_t *bc, int bc_len, const double *consts, int nconst, const double *const *slot_rows,
                        int n_slots, int cell_start_x, int cell_start_z, int block_y, int cell_w, double y_hoist,
                        int slab_layout, int col_xi, int col_zi, int cell_height, int flat_idx, double *result) {
  double stk[DFC_SLAB_STACK];
  int sp = 0;
  double bx, by, bz;
  if (slab_layout == 0) {
    int ix = flat_idx / cell_w;
    int iz = flat_idx % cell_w;
    bx = (double) (cell_start_x + ix);
    by = (double) block_y;
    bz = (double) (cell_start_z + iz);
  } else {
    /* XZ-hoist column: fixed (xi, zi), flat_idx walks Y (top → bottom). */
    bx = (double) (cell_start_x + col_xi);
    bz = (double) (cell_start_z + col_zi);
    if (cell_height <= 0) return 0;
    by = (double) (block_y + (cell_height - 1 - flat_idx));
  }
  (void) n_slots;

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
        if (s < 0 || s >= n_slots || !slot_rows[s]) return 0;
        push(stk, &sp, slot_rows[s][flat_idx]);
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

void dfc_slab_inner_eval_batch(const uint8_t *bc, int bc_len, const double *consts, int nconst,
                               const double *const *slot_rows, int n_slots, int cell_start_x, int cell_start_z,
                               int block_y, int cell_w, double y_hoist, int slab_layout, int col_xi, int col_zi,
                               int cell_height, double *out, int n) {
  if (!bc || bc_len <= 0 || !out || n <= 0 || cell_w <= 0) return;
  for (int i = 0; i < n; i++) {
    double v;
    if (!eval_at_flat(bc, bc_len, consts, nconst, slot_rows, n_slots, cell_start_x, cell_start_z, block_y, cell_w,
                       y_hoist, slab_layout, col_xi, col_zi, cell_height, i, &v)) {
      out[i] = 0.0;
    } else {
      out[i] = v;
    }
  }
}
