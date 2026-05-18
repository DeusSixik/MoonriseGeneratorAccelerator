__kernel void dfc_probe_math(__global double *out) {
    int gid = get_global_id(0);
    double x = (double) gid * 0.25 - 1.0;
    out[gid] = dfc_squeeze(x) + dfc_clamped_map(x, -1.0, 1.0, 0.0, 1.0);
}