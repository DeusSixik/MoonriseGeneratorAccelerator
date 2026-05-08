# my_math native template

This folder is a minimal example of an auto-discovered native module.

What makes it participate in the Gradle build:

- it lives under `common/natives/`
- it contains a `CMakeLists.txt`
- it contains a `native.properties`

This template intentionally does not include `native.properties`, so the build skips it by
default and prints a message explaining why.

What to customize:

- rename the folder if you want a different module name
- edit `CMakeLists.txt` to add more source files or compiler flags
- replace `c/my_math_jni.c` with your actual JNI entry points
- add `native.properties` when you are ready to include the module in builds

Expected output names by default:

- Windows: `my_math.dll`
- Linux: `libmy_math.so`
- macOS: `libmy_math.dylib`

See `docs/NATIVE_MODULES.md` for the full workflow and available build properties.
