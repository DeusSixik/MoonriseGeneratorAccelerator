#include "dfc_internal.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

#define DFC_BLEND_POOL 13
#define DFC_BLEND_TLS_MAX_N 512
static _Thread_local double dfc_blend_tls[DFC_BLEND_TLS_MAX_N * DFC_BLEND_POOL];
static _Thread_local int dfc_blend_index_tls[DFC_BLEND_TLS_MAX_N];

static double clamped_lerp_mc(double start, double end, double delta) {
  if (delta < 0.0) {
    return start;
  }
  return delta > 1.0 ? end : start + delta * (end - start);
}

static void wrap3_coords(double d0, double d1, double d2, double d11, double *wx, double *wy, double *wz) {
  *wx = dfc_wrap_axis(d0 * d11);
  *wy = dfc_wrap_axis(d1 * d11);
  *wz = dfc_wrap_axis(d2 * d11);
}

static int compact_limit_mask(const double *d16, int n, int want_min, int *active) {
  int active_n = 0;
  for (int i = 0; i < n; i++) {
    if (want_min ? (d16[i] < 1.0) : (d16[i] > 0.0)) {
      active[active_n++] = i;
    }
  }
  return active_n;
}

static void blended_limit_octaves_batch(const DfcImprovedNoise octaves[16], const uint8_t present[16],
                                        const int *active, int active_n, const double *d0, const double *d1,
                                        const double *d2, double d6, double *acc, double *axis_tmp,
                                        double *y_max_tmp, double *sample_tmp, double *wx, double *wy, double *wz,
                                        int use_avx2) {
  if (active_n <= 0) {
    return;
  }
  for (int j = 0; j < 16; j++) {
    if (!present[j]) {
      continue;
    }
    const double d11 = 1.0 / (double) (1LL << j);
    for (int k = 0; k < active_n; k++) {
      axis_tmp[k] = d0[active[k]] * d11;
    }
    dfc_wrap_axis_batch(axis_tmp, wx, active_n, use_avx2);
    for (int k = 0; k < active_n; k++) {
      axis_tmp[k] = d1[active[k]] * d11;
    }
    dfc_wrap_axis_batch(axis_tmp, wy, active_n, use_avx2);
    for (int k = 0; k < active_n; k++) {
      int i = active[k];
      axis_tmp[k] = d2[i] * d11;
      y_max_tmp[k] = d1[i] * d11;
      sample_tmp[k] = 0.0;
    }
    dfc_wrap_axis_batch(axis_tmp, wz, active_n, use_avx2);
    dfc_improved_noise_5_mad_add(&octaves[j], wx, wy, wz, d6 * d11, y_max_tmp, 1.0 / d11, sample_tmp, active_n);
    for (int k = 0; k < active_n; k++) {
      acc[active[k]] += sample_tmp[k];
    }
  }
}

/**
 * Tight scalar path for JNI single-sample and {@code n==1} batch: avoid pool setup,
 * three {@code dfc_wrap_axis_batch} calls per main octave, and {@code mad_add} overhead.
 */
static void blended_noise_one(const DfcBlendedSpec *s, double bx, double by, double bz, double *out) {
  double d0 = bx * s->xz_multiplier;
  double d1 = by * s->y_multiplier;
  double d2 = bz * s->xz_multiplier;
  double d3 = d0 / s->xz_factor;
  double d4 = d1 / s->y_factor;
  double d5 = d2 / s->xz_factor;
  double d6 = s->y_multiplier * s->smear_scale_multiplier;
  double d7 = d6 / s->y_factor;

  double d10 = 0.0;
  for (int oi = 0; oi < 8; oi++) {
    if (!s->main_present[oi]) {
      continue;
    }
    double d11 = 1.0 / (double) (1LL << oi);
    double wx, wy, wz;
    wrap3_coords(d3, d4, d5, d11, &wx, &wy, &wz);
    double n = dfc_improved_noise_5(&s->main_octaves[oi], wx, wy, wz, d7 * d11, d4 * d11);
    d10 += n / d11;
  }

  double d16 = (d10 / 10.0 + 1.0) * 0.5;
  double d8 = 0.0;
  double d9 = 0.0;

  if (d16 < 1.0) {
    for (int j = 0; j < 16; j++) {
      if (!s->min_present[j]) {
        continue;
      }
      double d11 = 1.0 / (double) (1LL << j);
      double wx, wy, wz;
      wrap3_coords(d0, d1, d2, d11, &wx, &wy, &wz);
      double n = dfc_improved_noise_5(&s->min_octaves[j], wx, wy, wz, d6 * d11, d1 * d11);
      d8 += n / d11;
    }
  }

  if (d16 > 0.0) {
    for (int j = 0; j < 16; j++) {
      if (!s->max_present[j]) {
        continue;
      }
      double d11 = 1.0 / (double) (1LL << j);
      double wx, wy, wz;
      wrap3_coords(d0, d1, d2, d11, &wx, &wy, &wz);
      double n = dfc_improved_noise_5(&s->max_octaves[j], wx, wy, wz, d6 * d11, d1 * d11);
      d9 += n / d11;
    }
  }

  *out = clamped_lerp_mc(d8 / 512.0, d9 / 512.0, d16) / 128.0;
  (void) s->max_value;
}

void dfc_blended_noise_batch(const DfcBlendedSpec *s, const double *xs, const double *ys, const double *zs, double *outs,
                             int n, int use_avx2) {
  if (!s || n <= 0 || !xs || !ys || !zs || !outs) {
    return;
  }
  if (n == 1) {
    blended_noise_one(s, xs[0], ys[0], zs[0], outs);
    return;
  }

  size_t words = (size_t) n * (size_t) DFC_BLEND_POOL;
  double *pool;
  double *heap = NULL;
  if (n <= DFC_BLEND_TLS_MAX_N) {
    pool = dfc_blend_tls;
  } else {
    heap = (double *) malloc(words * sizeof(double));
    if (!heap) {
      for (int i = 0; i < n; i++) {
        dfc_blended_noise_batch(s, &xs[i], &ys[i], &zs[i], &outs[i], 1, use_avx2);
      }
      return;
    }
    pool = heap;
  }

  double *d0 = pool;
  double *d1 = pool + n;
  double *d2 = pool + 2 * n;
  double *d3 = pool + 3 * n;
  double *d4 = pool + 4 * n;
  double *d5 = pool + 5 * n;
  double *d10 = pool + 6 * n;
  double *d16 = pool + 7 * n;
  double *d8a = pool + 8 * n;
  double *d9a = pool + 9 * n;
  double *wx = pool + 10 * n;
  double *wy = pool + 11 * n;
  double *wz = pool + 12 * n;
  for (int i = 0; i < n; i++) {
    d0[i] = xs[i] * s->xz_multiplier;
    d1[i] = ys[i] * s->y_multiplier;
    d2[i] = zs[i] * s->xz_multiplier;
    d3[i] = d0[i] / s->xz_factor;
    d4[i] = d1[i] / s->y_factor;
    d5[i] = d2[i] / s->xz_factor;
  }
  const double d6 = s->y_multiplier * s->smear_scale_multiplier;
  const double d7 = d6 / s->y_factor;
  for (int i = 0; i < n; i++) d10[i] = 0.0;

  /* Main: octave-outer, AVX2 wrap, fused mad_add. */
  for (int oi = 0; oi < 8; oi++) {
    if (!s->main_present[oi]) continue;
    const double d11 = 1.0 / (double) (1LL << oi);
    for (int i = 0; i < n; i++) {
      d8a[i] = d3[i] * d11;
    }
    dfc_wrap_axis_batch(d8a, wx, n, use_avx2);
    for (int i = 0; i < n; i++) {
      d8a[i] = d4[i] * d11;
    }
    dfc_wrap_axis_batch(d8a, wy, n, use_avx2);
    for (int i = 0; i < n; i++) {
      d8a[i] = d5[i] * d11;
    }
    dfc_wrap_axis_batch(d8a, wz, n, use_avx2);
    for (int i = 0; i < n; i++) {
      outs[i] = d4[i] * d11; /* y_max; outs overwritten at end */
    }
    dfc_improved_noise_5_mad_add(&s->main_octaves[oi], wx, wy, wz, d7 * d11, outs, 1.0 / d11, d10, n);
  }

  for (int i = 0; i < n; i++) {
    d16[i] = (d10[i] / 10.0 + 1.0) * 0.5;
  }
  for (int i = 0; i < n; i++) {
    d8a[i] = 0.0;
    d9a[i] = 0.0;
  }

  int *active = dfc_blend_index_tls;
  int *active_heap = NULL;
  if (n > DFC_BLEND_TLS_MAX_N) {
    active_heap = (int *) malloc((size_t) n * sizeof(int));
    if (!active_heap) {
      for (int i = 0; i < n; i++) {
        dfc_blended_noise_batch(s, &xs[i], &ys[i], &zs[i], &outs[i], 1, use_avx2);
      }
      free(heap);
      return;
    }
    active = active_heap;
  }

  /* Min/max: octave-outer batching with fixed per-sample masks; samples still accumulate j in order. */
  int active_n = compact_limit_mask(d16, n, 1, active);
  blended_limit_octaves_batch(s->min_octaves, s->min_present, active, active_n, d0, d1, d2, d6, d8a,
                              d3, d4, d5, wx, wy, wz, use_avx2);
  active_n = compact_limit_mask(d16, n, 0, active);
  blended_limit_octaves_batch(s->max_octaves, s->max_present, active, active_n, d0, d1, d2, d6, d9a,
                              d3, d4, d5, wx, wy, wz, use_avx2);

  for (int i = 0; i < n; i++) {
    outs[i] = clamped_lerp_mc(d8a[i] / 512.0, d9a[i] / 512.0, d16[i]) / 128.0;
  }
  (void) s->max_value;
  free(active_heap);
  free(heap);
}

void dfc_blended_noise_sample1(const DfcBlendedSpec *s, double bx, double by, double bz, double *out) {
  blended_noise_one(s, bx, by, bz, out);
}

DfcBlendedSpec *dfc_blended_spec_alloc_heap(const double *doubles6, const uint8_t *main_perm, const double *main_orig,
                                            const uint8_t *min_perm, const double *min_orig, const uint8_t *max_perm,
                                            const double *max_orig, const uint8_t *main_pres, const uint8_t *min_pres,
                                            const uint8_t *max_pres) {
  DfcBlendedSpec *b = (DfcBlendedSpec *) calloc(1, sizeof(DfcBlendedSpec));
  if (!b) {
    return NULL;
  }
  b->xz_multiplier = doubles6[0];
  b->y_multiplier = doubles6[1];
  b->xz_factor = doubles6[2];
  b->y_factor = doubles6[3];
  b->smear_scale_multiplier = doubles6[4];
  b->max_value = doubles6[5];
  for (int i = 0; i < 8; i++) {
    b->main_present[i] = main_pres[i];
    if (b->main_present[i]) {
      memcpy(b->main_octaves[i].p, main_perm + (size_t) i * 256, 256);
      b->main_octaves[i].xo = main_orig[(size_t) i * 3];
      b->main_octaves[i].yo = main_orig[(size_t) i * 3 + 1];
      b->main_octaves[i].zo = main_orig[(size_t) i * 3 + 2];
    }
  }
  for (int i = 0; i < 16; i++) {
    b->min_present[i] = min_pres[i];
    if (b->min_present[i]) {
      memcpy(b->min_octaves[i].p, min_perm + (size_t) i * 256, 256);
      b->min_octaves[i].xo = min_orig[(size_t) i * 3];
      b->min_octaves[i].yo = min_orig[(size_t) i * 3 + 1];
      b->min_octaves[i].zo = min_orig[(size_t) i * 3 + 2];
    }
  }
  for (int i = 0; i < 16; i++) {
    b->max_present[i] = max_pres[i];
    if (b->max_present[i]) {
      memcpy(b->max_octaves[i].p, max_perm + (size_t) i * 256, 256);
      b->max_octaves[i].xo = max_orig[(size_t) i * 3];
      b->max_octaves[i].yo = max_orig[(size_t) i * 3 + 1];
      b->max_octaves[i].zo = max_orig[(size_t) i * 3 + 2];
    }
  }
  return b;
}

void dfc_blended_spec_free(DfcBlendedSpec *s) { free(s); }
