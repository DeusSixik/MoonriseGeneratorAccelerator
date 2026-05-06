#include "dfc_internal.h"
#include <math.h>

static const int GRAD[16][3] = {
    {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
    {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
    {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
    {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
};

static int dfc_p(const DfcImprovedNoise *self, int index) {
  return (int) (self->p[index & 0xFF] & 0xFF);
}

static double grad_dot(int grad_index, double xf, double yf, double zf) {
  const int *g = GRAD[grad_index & 15];
  return (double) g[0] * xf + (double) g[1] * yf + (double) g[2] * zf;
}

static int mth_floor_double(double value) {
  int i = (int) value;
  return value < (double) i ? i - 1 : i;
}

static double smoothstep(double t) {
  return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

static double lerp(double d, double a, double b) {
  return a + d * (b - a);
}

static double lerp2(double d1, double d2, double s1, double e1, double s2, double e2) {
  return lerp(d2, lerp(d1, s1, e1), lerp(d1, s2, e2));
}

static double lerp3(double d1, double d2, double d3,
                    double s1, double e1, double s2, double e2, double s3, double e3, double s4, double e4) {
  return lerp(d3, lerp2(d1, d2, s1, e1, s2, e2), lerp2(d1, d2, s3, e3, s4, e4));
}

static double sample_and_lerp(const DfcImprovedNoise *self, int grid_x, int grid_y, int grid_z,
                              double delta_x, double weird_delta_y, double delta_z, double delta_y) {
  int i = dfc_p(self, grid_x);
  int j = dfc_p(self, grid_x + 1);
  int k = dfc_p(self, i + grid_y);
  int l = dfc_p(self, i + grid_y + 1);
  int i1 = dfc_p(self, j + grid_y);
  int j1 = dfc_p(self, j + grid_y + 1);
  double d0 = grad_dot(dfc_p(self, k + grid_z), delta_x, weird_delta_y, delta_z);
  double d1 = grad_dot(dfc_p(self, i1 + grid_z), delta_x - 1.0, weird_delta_y, delta_z);
  double d2 = grad_dot(dfc_p(self, l + grid_z), delta_x, weird_delta_y - 1.0, delta_z);
  double d3 = grad_dot(dfc_p(self, j1 + grid_z), delta_x - 1.0, weird_delta_y - 1.0, delta_z);
  double d4 = grad_dot(dfc_p(self, k + grid_z + 1), delta_x, weird_delta_y, delta_z - 1.0);
  double d5 = grad_dot(dfc_p(self, i1 + grid_z + 1), delta_x - 1.0, weird_delta_y, delta_z - 1.0);
  double d6 = grad_dot(dfc_p(self, l + grid_z + 1), delta_x, weird_delta_y - 1.0, delta_z - 1.0);
  double d7 = grad_dot(dfc_p(self, j1 + grid_z + 1), delta_x - 1.0, weird_delta_y - 1.0, delta_z - 1.0);
  double d8 = smoothstep(delta_x);
  double d9 = smoothstep(delta_y);
  double d10 = smoothstep(delta_z);
  return lerp3(d8, d9, d10, d0, d1, d2, d3, d4, d5, d6, d7);
}

double dfc_improved_eval5(const DfcImprovedNoise *self, double x, double y, double z, double y_scale,
                          double y_max) {
  double d0 = x + self->xo;
  double d1 = y + self->yo;
  double d2 = z + self->zo;
  int gi = mth_floor_double(d0);
  int gj = mth_floor_double(d1);
  int gk = mth_floor_double(d2);
  double d3 = d0 - (double) gi;
  double d4 = d1 - (double) gj;
  double d5 = d2 - (double) gk;
  double d6;
  if (y_scale != 0.0) {
    double d7 = (y_max >= 0.0 && y_max < d4) ? y_max : d4;
    d6 = (double) mth_floor_double(d7 / y_scale + 1.0E-7) * y_scale;
  } else {
    d6 = 0.0;
  }
  return sample_and_lerp(self, gi, gj, gk, d3, d4 - d6, d5, d4);
}

double dfc_improved_noise_3(const DfcImprovedNoise *self, double x, double y, double z) {
  return dfc_improved_eval5(self, x, y, z, 0.0, 0.0);
}

double dfc_improved_noise_5(const DfcImprovedNoise *self, double x, double y, double z,
                            double y_scale, double y_max) {
  return dfc_improved_eval5(self, x, y, z, y_scale, y_max);
}

void dfc_improved_noise_3_mad_add(const DfcImprovedNoise *self, const double *x, const double *y, const double *z,
                                 double amp, double *acc, int n) {
  if (!self || n <= 0) return;
  for (int i = 0; i < n; i++) {
    acc[i] += amp * dfc_improved_eval5(self, x[i], y[i], z[i], 0.0, 0.0);
  }
}

void dfc_improved_noise_5_mad_add(const DfcImprovedNoise *self, const double *x, const double *y, const double *z,
                                  double y_scale, const double *y_max, double amp, double *acc, int n) {
  if (!self || n <= 0) return;
  for (int i = 0; i < n; i++) {
    acc[i] += amp * dfc_improved_eval5(self, x[i], y[i], z[i], y_scale, y_max[i]);
  }
}
