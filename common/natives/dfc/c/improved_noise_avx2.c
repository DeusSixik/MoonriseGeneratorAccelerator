#include "dfc_internal.h"

#if defined(__AVX2__) && (defined(__x86_64__) || defined(_M_X64))

#include <immintrin.h>

void dfc_wrap_axis_batch_avx2(const double *in, double *out, int n) {
  __m256d div = _mm256_set1_pd(33554432.0);
  __m256d half = _mm256_set1_pd(0.5);
  int i = 0;
  for (; i + 4 <= n; i += 4) {
    __m256d v = _mm256_loadu_pd(in + i);
    __m256d scaled = _mm256_add_pd(_mm256_div_pd(v, div), half);
    double sbuf[4];
    _mm256_storeu_pd(sbuf, scaled);
    for (int j = 0; j < 4; j++) {
      double sv = sbuf[j];
      long truncated = (long) sv;
      long floored = sv < (double) truncated ? truncated - 1L : truncated;
      out[i + j] = in[i + j] - (double) floored * 33554432.0;
    }
  }
  for (; i < n; i++) {
    out[i] = dfc_wrap_axis(in[i]);
  }
}

#else

void dfc_wrap_axis_batch_avx2(const double *in, double *out, int n) {
  for (int i = 0; i < n; i++) {
    out[i] = dfc_wrap_axis(in[i]);
  }
}

#endif

void dfc_wrap_axis_batch(const double *in, double *out, int n, int use_avx2) {
#if defined(__AVX2__) && (defined(__x86_64__) || defined(_M_X64))
  if (use_avx2) {
    dfc_wrap_axis_batch_avx2(in, out, n);
    return;
  }
#endif
  for (int i = 0; i < n; i++) {
    out[i] = dfc_wrap_axis(in[i]);
  }
}
