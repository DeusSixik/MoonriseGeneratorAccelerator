#include "dfc_internal.h"

double dfc_wrap_axis(double v) {
  double scaled = v / 33554432.0 + 0.5;
  long truncated = (long) scaled;
  long floored = scaled < (double) truncated ? truncated - 1L : truncated;
  return v - (double) floored * 33554432.0;
}
