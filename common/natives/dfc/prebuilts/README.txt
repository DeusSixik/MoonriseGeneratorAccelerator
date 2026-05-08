Optional native libraries for platforms you are not building locally.

Every subdirectory under common/natives with a CMakeLists.txt is now discovered automatically
during the Gradle build. Put per-module overrides in native.properties when the folder name is
not the library base name or when you want extra classifiers / WSL Linux builds.

Layout (copy binaries from CI or other machines):

  prebuilts/windows_amd64/dfc_native.dll
  prebuilts/linux_x64/libdfc_native.so
  prebuilts/macos_aarch64/libdfc_native.dylib
  prebuilts/macos_x64/libdfc_native.dylib

Windows and Linux host natives are built automatically during common processResources and copied into the jar under:

  natives/<platform>/<library>

When dfc.buildNatives=true (or DFC_BUILD_NATIVES=1), the same staging path is used for
other supported hosts too:
  - If prebuilts/<host>/dfc_native.dll (or .so/.dylib) exists for the current OS, CMake is skipped for the host.
  - Otherwise CMake must be installed and on PATH (https://cmake.org/download/). On Windows, Visual Studio
    or Build Tools with C++ workload is typical; you can set -Pdfc.cmake=C:\\Program Files\\CMake\\bin\\cmake.exe
    if cmake is not on PATH.
  - On Windows, the build now also tries to produce linux_x64 automatically through WSL before falling back to
    prebuilts/linux_x64/libdfc_native.so. Disable that with -Pdfc.buildLinuxOnWindows=false.
  - If WSL does not auto-detect a Linux JDK, pass -Pdfc.linuxJavaHome=/usr/lib/jvm/<jdk> (or set
    DFC_LINUX_JAVA_HOME inside Windows) so cmake can find JNI headers for the Linux build.
If some expected native cannot be staged, the build logs an error, keeps going, and marks produced jars with
the -nn suffix.
