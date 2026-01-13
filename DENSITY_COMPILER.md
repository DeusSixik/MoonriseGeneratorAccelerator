| Density Function Name    | Supported |
|--------------------------|-----------|
| Constant                 | ✅         |
| FastAdd                  | ✅         |
| FastMax                  | ✅         |
| FastMin                  | ✅         |
| FastMul                  | ✅         |
| TwoArgumentSimple        | ✅         |
| TransformerWithContext   | ❌         |
| PureTransformer          | ❌         |
| BlendAlpha               | ✅         |
| BlendOffset              | ✅         |
| BeardifierMarker         | ✅         |
| HolderHolder             | ✅         |
| Marker                   | ✅         |
| EndIslandDensityFunction | 🟨        |
| WeirdScaledSampler       | ✅         |
| ShiftedNoise             | ✅         |
| RangeChoice              | ✅         |
| ShiftA                   | ✅         |
| ShiftB                   | ✅         |
| Shift                    | ✅         |
| BlendDensity             | ✅         |
| Clamp                    | ✅         |
| Mapped                   | ✅         |
| MulOrAdd                 | ✅         |
| Noise                    | ✅         |
| YClampedGradient         | ✅         |

- ✅ Full Support
- 🟨 Incomplete support. Some parts are not translated into ByteCode and use a wrapper for processing
- ❌ Unsupported