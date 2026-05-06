#ifndef DFC_INTERNAL_H
#define DFC_INTERNAL_H

#include <stdint.h>
#include <stddef.h>

typedef struct {
  uint8_t p[256];
  double xo;
  double yo;
  double zo;
} DfcImprovedNoise;

typedef struct {
  int n;
  double input_coord_scale;
  double *input_factors;
  double *amp_factors;
  DfcImprovedNoise *octaves;
} DfcPerlinBranch;

typedef struct {
  double value_factor;
  DfcPerlinBranch first;
  DfcPerlinBranch second;
} DfcNormalNoiseStack;

typedef struct {
  double xz_multiplier;
  double y_multiplier;
  double xz_factor;
  double y_factor;
  double smear_scale_multiplier;
  double max_value;
  DfcImprovedNoise main_octaves[8];
  uint8_t main_present[8];
  DfcImprovedNoise min_octaves[16];
  uint8_t min_present[16];
  DfcImprovedNoise max_octaves[16];
  uint8_t max_present[16];
} DfcBlendedSpec;

double dfc_wrap_axis(double v);

void dfc_wrap_axis_batch(const double *in, double *out, int n, int use_avx2);

double dfc_improved_noise_3(const DfcImprovedNoise *self, double x, double y, double z);

double dfc_improved_noise_5(const DfcImprovedNoise *self, double x, double y, double z,
                            double y_scale, double y_max);

/**
 * Non-static core used by the normal stack sample1 path to avoid a wrapper hop; same as
 * {@code noise_5} and {@code noise_3(…,0,0)}.
 */
double dfc_improved_eval5(const DfcImprovedNoise *self, double x, double y, double z, double y_scale, double y_max);

/** acc[i] += amp * sample3 for each i (used by normal stack + inner batch paths). */
void dfc_improved_noise_3_mad_add(const DfcImprovedNoise *self, const double *x, const double *y, const double *z,
                                  double amp, double *acc, int n);
/** acc[i] += amp * sample5; y_max varies per i, y_scale is the same for all i. */
void dfc_improved_noise_5_mad_add(const DfcImprovedNoise *self, const double *x, const double *y, const double *z,
                                  double y_scale, const double *y_max, double amp, double *acc, int n);

void dfc_normal_noise_stack_sample1(const DfcNormalNoiseStack *s, double cx, double cy, double cz, double *out);

void dfc_normal_noise_stack_batch(const DfcNormalNoiseStack *s, const double *xs, const double *ys,
                                  const double *zs, double *outs, int n, int use_avx2);

void dfc_slab_inner_eval_batch(const uint8_t *bc, int bc_len, const double *consts, int nconst,
                               const double *const *slot_rows, int n_slots, int cell_start_x, int cell_start_z,
                               int block_y, int cell_w, double y_hoist, int slab_layout, int col_xi, int col_zi,
                               int cell_height, double *out, int n);

void dfc_blended_noise_sample1(const DfcBlendedSpec *s, double bx, double by, double bz, double *out);

void dfc_blended_noise_batch(const DfcBlendedSpec *s, const double *xs, const double *ys, const double *zs,
                             double *outs, int n, int use_avx2);

DfcNormalNoiseStack *dfc_normal_stack_alloc_heap(double value_factor,
                                                 int n0, double scale0, const double *in0, const double *amp0,
                                                 const uint8_t *perm0, const double *orig0,
                                                 int n1, double scale1, const double *in1, const double *amp1,
                                                 const uint8_t *perm1, const double *orig1);

void dfc_normal_stack_free(DfcNormalNoiseStack *s);

DfcBlendedSpec *dfc_blended_spec_alloc_heap(const double *doubles6,
                                            const uint8_t *main_perm, const double *main_orig,
                                            const uint8_t *min_perm, const double *min_orig,
                                            const uint8_t *max_perm, const double *max_orig,
                                            const uint8_t *main_pres, const uint8_t *min_pres,
                                            const uint8_t *max_pres);

void dfc_blended_spec_free(DfcBlendedSpec *s);

#endif
