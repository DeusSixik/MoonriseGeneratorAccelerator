#include "dfc_internal.h"
#include <stdlib.h>
#include <string.h>

/* One slab batch is at most a few hundred points; avoid heap on the hot path. */
#define DFC_NSTACK_WXYZ_CAP 640
static _Thread_local double dfc_nstack_tls_wxyz[DFC_NSTACK_WXYZ_CAP * 3];

static void branch_sum_batch_add(const DfcPerlinBranch *b, const double *xs, const double *ys, const double *zs,
                                 double *acc, int n, int use_avx2, double *wx, double *wy, double *wz) {
  if (!b || b->n <= 0 || n <= 0) return;
  double sc = b->input_coord_scale;
  for (int o = 0; o < b->n; o++) {
    double inf = b->input_factors[o];
    double amp = b->amp_factors[o];
    if (sc != 1.0) {
      for (int i = 0; i < n; i++) {
        wx[i] = xs[i] * sc * inf;
        wy[i] = ys[i] * sc * inf;
        wz[i] = zs[i] * sc * inf;
      }
    } else {
      for (int i = 0; i < n; i++) {
        wx[i] = xs[i] * inf;
        wy[i] = ys[i] * inf;
        wz[i] = zs[i] * inf;
      }
    }
    dfc_wrap_axis_batch(wx, wx, n, use_avx2);
    dfc_wrap_axis_batch(wy, wy, n, use_avx2);
    dfc_wrap_axis_batch(wz, wz, n, use_avx2);
    dfc_improved_noise_3_mad_add(&b->octaves[o], wx, wy, wz, amp, acc, n);
  }
}

static double branch_sum(const DfcPerlinBranch *b, double cx, double cy, double cz) {
  double x = cx;
  double y = cy;
  double z = cz;
  if (b->input_coord_scale != 1.0) {
    x *= b->input_coord_scale;
    y *= b->input_coord_scale;
    z *= b->input_coord_scale;
  }
  double sum = 0.0;
  for (int i = 0; i < b->n; i++) {
    double inf = b->input_factors[i];
    double wx = dfc_wrap_axis(x * inf);
    double wy = dfc_wrap_axis(y * inf);
    double wz = dfc_wrap_axis(z * inf);
    double amp = b->amp_factors[i];
    sum += amp * dfc_improved_eval5(&b->octaves[i], wx, wy, wz, 0.0, 0.0);
  }
  return sum;
}

void dfc_normal_noise_stack_sample1(const DfcNormalNoiseStack *s, double cx, double cy, double cz, double *out) {
  double acc = branch_sum(&s->first, cx, cy, cz);
  acc += branch_sum(&s->second, cx, cy, cz);
  *out = acc * s->value_factor;
}

void dfc_normal_noise_stack_batch(const DfcNormalNoiseStack *s, const double *xs, const double *ys,
                                  const double *zs, double *outs, int n, int use_avx2) {
  if (!s || n <= 0) return;
  int need = n * 3;
  double *wx;
  double *tmp_heap = NULL;
  if (need <= DFC_NSTACK_WXYZ_CAP * 3) {
    wx = dfc_nstack_tls_wxyz;
  } else {
    size_t bytes = (size_t) n * sizeof(double) * 3;
    tmp_heap = (double *) malloc(bytes);
    if (!tmp_heap) {
      for (int i = 0; i < n; i++) {
        dfc_normal_noise_stack_sample1(s, xs[i], ys[i], zs[i], &outs[i]);
      }
      return;
    }
    wx = tmp_heap;
  }
  double *wy = wx + n;
  double *wz = wx + 2 * (size_t) n;
  for (int i = 0; i < n; i++) {
    outs[i] = 0.0;
  }
  branch_sum_batch_add(&s->first, xs, ys, zs, outs, n, use_avx2, wx, wy, wz);
  branch_sum_batch_add(&s->second, xs, ys, zs, outs, n, use_avx2, wx, wy, wz);
  double vf = s->value_factor;
  for (int i = 0; i < n; i++) {
    outs[i] *= vf;
  }
  free(tmp_heap);
}

DfcNormalNoiseStack *dfc_normal_stack_alloc_heap(double value_factor,
                                                 int n0, double scale0, const double *in0, const double *amp0,
                                                 const uint8_t *perm0, const double *orig0,
                                                 int n1, double scale1, const double *in1, const double *amp1,
                                                 const uint8_t *perm1, const double *orig1) {
  DfcNormalNoiseStack *s = (DfcNormalNoiseStack *) calloc(1, sizeof(DfcNormalNoiseStack));
  if (!s) return NULL;
  s->value_factor = value_factor;

  s->first.n = n0;
  s->first.input_coord_scale = scale0;
  if (n0 > 0) {
    s->first.input_factors = (double *) malloc((size_t) n0 * sizeof(double));
    s->first.amp_factors = (double *) malloc((size_t) n0 * sizeof(double));
    s->first.octaves = (DfcImprovedNoise *) malloc((size_t) n0 * sizeof(DfcImprovedNoise));
    if (!s->first.input_factors || !s->first.amp_factors || !s->first.octaves) goto fail;
    memcpy(s->first.input_factors, in0, (size_t) n0 * sizeof(double));
    memcpy(s->first.amp_factors, amp0, (size_t) n0 * sizeof(double));
    for (int i = 0; i < n0; i++) {
      memcpy(s->first.octaves[i].p, perm0 + (size_t) i * 256, 256);
      s->first.octaves[i].xo = orig0[(size_t) i * 3];
      s->first.octaves[i].yo = orig0[(size_t) i * 3 + 1];
      s->first.octaves[i].zo = orig0[(size_t) i * 3 + 2];
    }
  }

  s->second.n = n1;
  s->second.input_coord_scale = scale1;
  if (n1 > 0) {
    s->second.input_factors = (double *) malloc((size_t) n1 * sizeof(double));
    s->second.amp_factors = (double *) malloc((size_t) n1 * sizeof(double));
    s->second.octaves = (DfcImprovedNoise *) malloc((size_t) n1 * sizeof(DfcImprovedNoise));
    if (!s->second.input_factors || !s->second.amp_factors || !s->second.octaves) goto fail;
    memcpy(s->second.input_factors, in1, (size_t) n1 * sizeof(double));
    memcpy(s->second.amp_factors, amp1, (size_t) n1 * sizeof(double));
    for (int i = 0; i < n1; i++) {
      memcpy(s->second.octaves[i].p, perm1 + (size_t) i * 256, 256);
      s->second.octaves[i].xo = orig1[(size_t) i * 3];
      s->second.octaves[i].yo = orig1[(size_t) i * 3 + 1];
      s->second.octaves[i].zo = orig1[(size_t) i * 3 + 2];
    }
  }
  return s;

fail:
  dfc_normal_stack_free(s);
  return NULL;
}

void dfc_normal_stack_free(DfcNormalNoiseStack *s) {
  if (!s) return;
  free(s->first.input_factors);
  free(s->first.amp_factors);
  free(s->first.octaves);
  free(s->second.input_factors);
  free(s->second.amp_factors);
  free(s->second.octaves);
  free(s);
}
