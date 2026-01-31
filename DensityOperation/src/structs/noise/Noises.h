//
// Created by sixik on 29.01.2026.
//

#ifndef DENSITYOPERATION_NOISES_H
#define DENSITYOPERATION_NOISES_H

#include <cmath>
#include <cstdint>
#include <iostream>
#include <vector>

static const int GRADIENT[16][3] = {
    {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
    {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
    {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
    {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
};

namespace Mth {
    // Точная копия Mth.floor()
    inline int floor(double value) {
        int i = static_cast<int>(value);
        return value < static_cast<double>(i) ? i - 1 : i;
    }

    // 6t^5 - 15t^4 + 10t^3
    inline double smoothstep(double x) {
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    // Lerp (Linear Interpolation)
    inline double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    // Trilinear Interpolation
    inline double lerp3(double dx, double dy, double dz,
                        double v000, double v100, double v010, double v110,
                        double v001, double v101, double v011, double v111) {
        return lerp(dz,
            lerp(dy,
                lerp(dx, v000, v100),
                lerp(dx, v010, v110)
            ),
            lerp(dy,
                lerp(dx, v001, v101),
                lerp(dx, v011, v111)
            )
        );
    }
}

namespace Density {

    struct ImprovedNoise {
        double xo;
        double yo;
        double zo;
        std::byte p[256];

        double noise(const double d, const double e, const double f) {
            return noise(d, e, f, 0, 0);
        }

        double noise(const double d, const double e, const double f, const double g, const double h) {
            double i = d + xo;
            double j = e + yo;
            double k = f + zo;

            int l = floor(i);
            int m = floor(j);
            int n = floor(k);

            double o = i - l;
            double p = j - m;
            double q = k - n;

            double s;

            if (g != 0.0) {
                double r;
                if (h >= 0.0 && h < p) {
                    r = h;
                } else {
                    r = p;
                }

                s = floor(r / g + .0E-7) * g;
            } else {
                s = 0.0;
            }

            return sampleAndLerp(l, m, n, o, p - s, q, p);
        }

    private:
        double sampleAndLerp(int i, int j, int k, double d, double e, double f, double g) {
             int l = pM(i);
             int m = pM(i + 1);
             int n = pM(l + j);
             int o = pM(l + j + 1);
             int p = pM(m + j);
             int q = pM(m + j + 1);
             double h = gradDot(pM(n + k), d, e, f);
             double r = gradDot(pM(p + k), d - 1.0, e, f);
             double s = gradDot(pM(o + k), d, e - 1.0, f);
             double t = gradDot(pM(q + k), d - 1.0, e - 1.0, f);
             double u = gradDot(pM(n + k + 1), d, e, f - 1.0);
             double v = gradDot(pM(p + k + 1), d - 1.0, e, f - 1.0);
             double w = gradDot(pM(o + k + 1), d, e - 1.0, f - 1.0);
             double x = gradDot(pM(q + k + 1), d - 1.0, e - 1.0, f - 1.0);
             double y = Mth::smoothstep(d);
             double z = Mth::smoothstep(g);
             double aa = Mth::smoothstep(f);
             return Mth::lerp3(y, z, aa, h, r, s, t, u, v, w, x);
        }

        int pM(int i) {
             return static_cast<uint8_t>(p[i & 255]) & 255;
        }

         double gradDot(int i, double d, double e, double f) {
             return dot(GRADIENT[i & 15], d, e, f);
         }

        double dot(const int* is, double d, double e, double f) {
             return is[0] * d + is[1] * e + is[2] * f;
         }

    };

    struct NoiseOctaveData {
        double xo;
        double yo;
        double zo;
        std::byte p[256];
    };

    struct PerlinNoise {
        int firstOctave;
        std::vector<double> amplitudes;
        std::vector<NoiseOctaveData> noiseLevels;
        double lowestFreqValueFactor;
        double lowestFreqInputFactor;
        double maxValue;
    };

}

#endif //DENSITYOPERATION_NOISES_H