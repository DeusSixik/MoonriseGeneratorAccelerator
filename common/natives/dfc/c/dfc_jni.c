#include "dfc_internal.h"
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#ifdef _WIN32
#include <intrin.h>
#endif

JNIEXPORT jint JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_nativeQueryCpu(JNIEnv *env,
                                                                                               jclass clazz) {
  (void) env;
  (void) clazz;
  int avx2 = 0;
#if (defined(__GNUC__) || defined(__clang__)) && (defined(__x86_64__) || defined(__i386__))
  __builtin_cpu_init();
  avx2 = __builtin_cpu_supports("avx2") ? 1 : 0;
#elif defined(_WIN32) && (defined(_M_X64) || defined(_M_IX86))
  int info[4];
  __cpuid(info, 0);
  if (info[0] >= 7) {
    __cpuidex(info, 7, 0);
    avx2 = (info[1] & (1 << 5)) ? 1 : 0;
  }
#endif
  return avx2;
}

JNIEXPORT jlong JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_allocNormalNoiseStack(
    JNIEnv *env, jclass clazz, jdouble value_factor, jint n0, jdouble scale0, jdoubleArray in0, jdoubleArray amp0,
    jbyteArray perm0, jdoubleArray orig0, jint n1, jdouble scale1, jdoubleArray in1, jdoubleArray amp1,
    jbyteArray perm1, jdoubleArray orig1) {
  (void) clazz;
  if (n0 < 0 || n1 < 0) return 0;
  jdouble *pin0 = NULL, *pamp0 = NULL, *porig0 = NULL;
  jdouble *pin1 = NULL, *pamp1 = NULL, *porig1 = NULL;
  jbyte *pperm0 = NULL, *pperm1 = NULL;

  if (n0 > 0) {
    pin0 = (*env)->GetDoubleArrayElements(env, in0, NULL);
    pamp0 = (*env)->GetDoubleArrayElements(env, amp0, NULL);
    porig0 = (*env)->GetDoubleArrayElements(env, orig0, NULL);
    pperm0 = (*env)->GetByteArrayElements(env, perm0, NULL);
    if (!pin0 || !pamp0 || !porig0 || !pperm0) goto fail0;
  }
  if (n1 > 0) {
    pin1 = (*env)->GetDoubleArrayElements(env, in1, NULL);
    pamp1 = (*env)->GetDoubleArrayElements(env, amp1, NULL);
    porig1 = (*env)->GetDoubleArrayElements(env, orig1, NULL);
    pperm1 = (*env)->GetByteArrayElements(env, perm1, NULL);
    if (!pin1 || !pamp1 || !porig1 || !pperm1) goto fail0;
  }

  DfcNormalNoiseStack *s = dfc_normal_stack_alloc_heap(
      (double) value_factor, n0, (double) scale0, pin0, pamp0, (const uint8_t *) pperm0, porig0, n1, (double) scale1,
      pin1, pamp1, (const uint8_t *) pperm1, porig1);

  if (n0 > 0) {
    (*env)->ReleaseDoubleArrayElements(env, in0, pin0, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, amp0, pamp0, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, orig0, porig0, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, perm0, pperm0, JNI_ABORT);
  }
  if (n1 > 0) {
    (*env)->ReleaseDoubleArrayElements(env, in1, pin1, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, amp1, pamp1, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, orig1, porig1, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, perm1, pperm1, JNI_ABORT);
  }
  return (jlong) (uintptr_t) s;

fail0:
  if (pin0) (*env)->ReleaseDoubleArrayElements(env, in0, pin0, JNI_ABORT);
  if (pamp0) (*env)->ReleaseDoubleArrayElements(env, amp0, pamp0, JNI_ABORT);
  if (porig0) (*env)->ReleaseDoubleArrayElements(env, orig0, porig0, JNI_ABORT);
  if (pperm0) (*env)->ReleaseByteArrayElements(env, perm0, pperm0, JNI_ABORT);
  if (pin1) (*env)->ReleaseDoubleArrayElements(env, in1, pin1, JNI_ABORT);
  if (pamp1) (*env)->ReleaseDoubleArrayElements(env, amp1, pamp1, JNI_ABORT);
  if (porig1) (*env)->ReleaseDoubleArrayElements(env, orig1, porig1, JNI_ABORT);
  if (pperm1) (*env)->ReleaseByteArrayElements(env, perm1, pperm1, JNI_ABORT);
  return 0;
}

JNIEXPORT void JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_releaseNormalNoiseStack(JNIEnv *env,
                                                                                                        jclass clazz,
                                                                                                        jlong handle) {
  (void) env;
  (void) clazz;
  if (handle == 0) return;
  dfc_normal_stack_free((DfcNormalNoiseStack *) (uintptr_t) handle);
}

JNIEXPORT void JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_normalNoiseStackBatch(
    JNIEnv *env, jclass clazz, jlong handle, jdoubleArray xs, jdoubleArray ys, jdoubleArray zs, jdoubleArray outs,
    jint n, jboolean use_avx2) {
  (void) clazz;
  if (handle == 0 || n <= 0) return;
  DfcNormalNoiseStack *s = (DfcNormalNoiseStack *) (uintptr_t) handle;
  jdouble *px = (*env)->GetDoubleArrayElements(env, xs, NULL);
  jdouble *py = (*env)->GetDoubleArrayElements(env, ys, NULL);
  jdouble *pz = (*env)->GetDoubleArrayElements(env, zs, NULL);
  jdouble *po = (*env)->GetDoubleArrayElements(env, outs, NULL);
  if (!px || !py || !pz || !po) goto done;
  dfc_normal_noise_stack_batch(s, px, py, pz, po, (int) n, use_avx2 ? 1 : 0);
done:
  if (px) (*env)->ReleaseDoubleArrayElements(env, xs, px, JNI_ABORT);
  if (py) (*env)->ReleaseDoubleArrayElements(env, ys, py, JNI_ABORT);
  if (pz) (*env)->ReleaseDoubleArrayElements(env, zs, pz, JNI_ABORT);
  if (po) (*env)->ReleaseDoubleArrayElements(env, outs, po, 0);
}

JNIEXPORT jdouble JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_normalNoiseStackSample1(
    JNIEnv *env, jclass clazz, jlong handle, jdouble cx, jdouble cy, jdouble cz) {
  (void) env;
  (void) clazz;
  if (handle == 0) return 0.0;
  double out;
  dfc_normal_noise_stack_sample1((DfcNormalNoiseStack *) (uintptr_t) handle, cx, cy, cz, &out);
  return out;
}

JNIEXPORT jlong JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_allocBlendedSpec(
    JNIEnv *env, jclass clazz, jdoubleArray doubles6, jbyteArray main_perm, jdoubleArray main_orig, jbyteArray min_perm,
    jdoubleArray min_orig, jbyteArray max_perm, jdoubleArray max_orig, jbyteArray main_pres, jbyteArray min_pres,
    jbyteArray max_pres) {
  (void) clazz;
  jdouble *d6 = (*env)->GetDoubleArrayElements(env, doubles6, NULL);
  jbyte *mp = (*env)->GetByteArrayElements(env, main_perm, NULL);
  jdouble *mo = (*env)->GetDoubleArrayElements(env, main_orig, NULL);
  jbyte *np = (*env)->GetByteArrayElements(env, min_perm, NULL);
  jdouble *no = (*env)->GetDoubleArrayElements(env, min_orig, NULL);
  jbyte *xp = (*env)->GetByteArrayElements(env, max_perm, NULL);
  jdouble *xo = (*env)->GetDoubleArrayElements(env, max_orig, NULL);
  jbyte *mpr = (*env)->GetByteArrayElements(env, main_pres, NULL);
  jbyte *npr = (*env)->GetByteArrayElements(env, min_pres, NULL);
  jbyte *xpr = (*env)->GetByteArrayElements(env, max_pres, NULL);
  if (!d6 || !mp || !mo || !np || !no || !xp || !xo || !mpr || !npr || !xpr) {
    if (d6) (*env)->ReleaseDoubleArrayElements(env, doubles6, d6, JNI_ABORT);
    if (mp) (*env)->ReleaseByteArrayElements(env, main_perm, mp, JNI_ABORT);
    if (mo) (*env)->ReleaseDoubleArrayElements(env, main_orig, mo, JNI_ABORT);
    if (np) (*env)->ReleaseByteArrayElements(env, min_perm, np, JNI_ABORT);
    if (no) (*env)->ReleaseDoubleArrayElements(env, min_orig, no, JNI_ABORT);
    if (xp) (*env)->ReleaseByteArrayElements(env, max_perm, xp, JNI_ABORT);
    if (xo) (*env)->ReleaseDoubleArrayElements(env, max_orig, xo, JNI_ABORT);
    if (mpr) (*env)->ReleaseByteArrayElements(env, main_pres, mpr, JNI_ABORT);
    if (npr) (*env)->ReleaseByteArrayElements(env, min_pres, npr, JNI_ABORT);
    if (xpr) (*env)->ReleaseByteArrayElements(env, max_pres, xpr, JNI_ABORT);
    return 0;
  }
  DfcBlendedSpec *b = dfc_blended_spec_alloc_heap(d6, (const uint8_t *) mp, mo, (const uint8_t *) np, no,
                                                  (const uint8_t *) xp, xo, (const uint8_t *) mpr, (const uint8_t *) npr,
                                                  (const uint8_t *) xpr);
  (*env)->ReleaseDoubleArrayElements(env, doubles6, d6, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, main_perm, mp, JNI_ABORT);
  (*env)->ReleaseDoubleArrayElements(env, main_orig, mo, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, min_perm, np, JNI_ABORT);
  (*env)->ReleaseDoubleArrayElements(env, min_orig, no, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, max_perm, xp, JNI_ABORT);
  (*env)->ReleaseDoubleArrayElements(env, max_orig, xo, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, main_pres, mpr, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, min_pres, npr, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, max_pres, xpr, JNI_ABORT);
  return (jlong) (uintptr_t) b;
}

JNIEXPORT void JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_releaseBlendedSpec(JNIEnv *env,
                                                                                                   jclass clazz,
                                                                                                   jlong handle) {
  (void) env;
  (void) clazz;
  if (handle == 0) return;
  dfc_blended_spec_free((DfcBlendedSpec *) (uintptr_t) handle);
}

JNIEXPORT void JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_blendedNoiseBatch(
    JNIEnv *env, jclass clazz, jlong handle, jdoubleArray xs, jdoubleArray ys, jdoubleArray zs, jdoubleArray outs,
    jint n, jboolean use_avx2) {
  (void) clazz;
  if (handle == 0 || n <= 0) return;
  DfcBlendedSpec *b = (DfcBlendedSpec *) (uintptr_t) handle;
  jdouble *px = (*env)->GetDoubleArrayElements(env, xs, NULL);
  jdouble *py = (*env)->GetDoubleArrayElements(env, ys, NULL);
  jdouble *pz = (*env)->GetDoubleArrayElements(env, zs, NULL);
  jdouble *po = (*env)->GetDoubleArrayElements(env, outs, NULL);
  if (!px || !py || !pz || !po) goto done;
  dfc_blended_noise_batch(b, px, py, pz, po, (int) n, use_avx2 ? 1 : 0);
done:
  if (px) (*env)->ReleaseDoubleArrayElements(env, xs, px, JNI_ABORT);
  if (py) (*env)->ReleaseDoubleArrayElements(env, ys, py, JNI_ABORT);
  if (pz) (*env)->ReleaseDoubleArrayElements(env, zs, pz, JNI_ABORT);
  if (po) (*env)->ReleaseDoubleArrayElements(env, outs, po, 0);
}

JNIEXPORT jdouble JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_blendedNoiseSample1(
    JNIEnv *env, jclass clazz, jlong handle, jdouble bx, jdouble by, jdouble bz) {
  (void) env;
  (void) clazz;
  if (handle == 0) return 0.0;
  double o;
  dfc_blended_noise_sample1((DfcBlendedSpec *) (uintptr_t) handle, bx, by, bz, &o);
  return o;
}

JNIEXPORT void JNICALL Java_dev_sixik_generator_1accelerator_common_density_compiler_natives_DfcNativeBridge_nativeSlabInnerEval(
    JNIEnv *env, jclass clazz, jbyteArray bc, jdoubleArray consts, jdoubleArray slot_rows_flat, jint slot_count, jint first_noise_x,
    jint first_noise_z, jint block_y, jint cell_w, jint slab_layout, jint col_xi, jint col_zi, jint cell_height,
    jdouble y_hoist, jdoubleArray out, jint n) {
  (void) clazz;
  if (!bc || !consts || !slot_rows_flat || !out || n <= 0 || cell_w <= 0 || slot_count < 0) return;
  jsize bc_len = (*env)->GetArrayLength(env, bc);
  if (bc_len <= 0) return;
  jbyte *pbc = (*env)->GetByteArrayElements(env, bc, NULL);
  jdouble *pconst = (*env)->GetDoubleArrayElements(env, consts, NULL);
  jdouble *pslots = (*env)->GetDoubleArrayElements(env, slot_rows_flat, NULL);
  jdouble *pout = (*env)->GetDoubleArrayElements(env, out, NULL);
  if (!pbc || !pconst || !pslots || !pout) goto done;
  jsize nconst = (*env)->GetArrayLength(env, consts);
  dfc_slab_inner_eval_batch((const uint8_t *) pbc, (int) bc_len, pconst, (int) nconst, pslots, (int) slot_count,
                            (int) n, (int) first_noise_x, (int) first_noise_z, (int) block_y, (int) cell_w,
                            (double) y_hoist, (int) slab_layout, (int) col_xi, (int) col_zi,
                            (int) cell_height, pout, (int) n);
done:
  if (pbc) (*env)->ReleaseByteArrayElements(env, bc, pbc, JNI_ABORT);
  if (pconst) (*env)->ReleaseDoubleArrayElements(env, consts, pconst, JNI_ABORT);
  if (pslots) (*env)->ReleaseDoubleArrayElements(env, slot_rows_flat, pslots, JNI_ABORT);
  if (pout) (*env)->ReleaseDoubleArrayElements(env, out, pout, 0);
}
