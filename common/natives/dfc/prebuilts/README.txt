Optional native libraries for platforms you are not building locally.

Layout (copy binaries from CI or other machines):

  prebuilts/windows_amd64/dfc_native.dll
  prebuilts/linux_x64/libdfc_native.so
  prebuilts/macos_aarch64/libdfc_native.dylib
  prebuilts/macos_x64/libdfc_native.dylib

Windows and Linux host natives are built automatically during common processResources.
When dfc.buildNatives=true (or DFC_BUILD_NATIVES=1), the same staging path is used for
other supported hosts too:
  - If prebuilts/<host>/dfc_native.dll (or .so/.dylib) exists for the current OS, CMake is skipped for the host.
  - Otherwise CMake must be installed and on PATH (https://cmake.org/download/). On Windows, Visual Studio
    or Build Tools with C++ workload is typical; you can set -Pdfc.cmake=C:\\Program Files\\CMake\\bin\\cmake.exe
    if cmake is not on PATH.
Missing Windows/Linux prebuilts for the non-host platform are skipped unless you pass
-Pdfc.requireAllNatives=true (then the build fails).
