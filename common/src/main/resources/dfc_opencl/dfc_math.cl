// Shared OpenCL helpers for the experimental DFC backend.
// The backend requires fp64 because Minecraft density math is double based.

#ifdef cl_khr_fp64
#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#elif defined(cl_amd_fp64)
#pragma OPENCL EXTENSION cl_amd_fp64 : enable
#endif

#pragma OPENCL FP_CONTRACT OFF

#ifndef DFC_OPENCL_MATH_CL
#define DFC_OPENCL_MATH_CL

inline int dfc_java_floor(double value) {
    int truncated = (int) value;
    return value < (double) truncated ? truncated - 1 : truncated;
}

inline long dfc_java_floor_long(double value) {
    long truncated = (long) value;
    return value < (double) truncated ? truncated - 1L : truncated;
}

inline double dfc_lerp(double delta, double start, double end) {
    return start + delta * (end - start);
}

inline double dfc_clamped_lerp(double start, double end, double delta) {
    if (delta < 0.0) {
        return start;
    }
    return delta > 1.0 ? end : dfc_lerp(delta, start, end);
}

inline double dfc_clamped_map(double value, double old_min, double old_max,
                              double new_min, double new_max) {
    double delta = (value - old_min) / (old_max - old_min);
    return dfc_clamped_lerp(new_min, new_max, delta);
}

inline double dfc_squeeze(double value) {
    double clamped = value < -1.0 ? -1.0 : (value > 1.0 ? 1.0 : value);
    return clamped / 2.0 - clamped * clamped * clamped / 24.0;
}

inline double dfc_wrap_axis(double value) {
    double scaled = value / 33554432.0 + 0.5;
    long floored = dfc_java_floor_long(scaled);
    return value - (double) floored * 33554432.0;
}

#endif // DFC_OPENCL_MATH_CL