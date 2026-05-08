# Native Modules

This project can bundle one or more JNI/native modules from `common/natives/*`.

## Auto-discovery

During the Gradle build, every direct subdirectory of `common/natives` is treated as a native
module only if it contains both:

- `CMakeLists.txt`
- `native.properties`

That means a folder like this is enough to make a new module participate in the build:

```text
common/
  natives/
    my_math/
      CMakeLists.txt
      native.properties
      c/
        my_math_jni.c
```

No extra Gradle registration is required.

If a folder has `CMakeLists.txt` but does not have `native.properties`, Gradle prints a skip
message during configuration and does not include that module in the native build pipeline.

## Output layout

Built or staged native libraries are copied into the jar under:

```text
natives/<platform>/<library file>
```

Examples:

```text
natives/windows_amd64/my_math.dll
natives/linux_x64/libmy_math.so
natives/macos_x64/libmy_math.dylib
```

Prebuilt binaries can be supplied from:

```text
common/natives/<module>/prebuilts/<platform>/<library file>
```

## Default conventions

If `native.properties` is present but minimal:

- the module name is the folder name under `common/natives`
- the native library base name defaults to the folder name
- the build targets the current host platform
- on Windows, Linux is not built through WSL unless explicitly enabled

For example, `common/natives/my_math` defaults to:

- `my_math.dll` on Windows
- `libmy_math.so` on Linux
- `libmy_math.dylib` on macOS

## `native.properties`

Each module may define `common/natives/<module>/native.properties` to override the defaults.

Supported keys:

```properties
libraryBaseName=my_math
classifiers=windows_amd64,linux_x64,macos_x64,macos_aarch64
buildLinuxOnWindows=true
```

Meaning:

- `libraryBaseName`: overrides the produced library filename
- `classifiers`: declares which platform classifiers the module supports
- `buildLinuxOnWindows`: enables Linux builds through WSL when running on Windows

## Gradle properties

Global properties:

- `-Pnative.cmake=<path>`
- `-Pnative.cmakeGenerator=<name>`
- `-Pnative.wslDistribution=<distro>`
- `-Pnative.linuxJavaHome=<linux-jdk-home>`

Per-module properties:

- `-Pnative.<module>.build=true|false`
- `-Pnative.<module>.requireAll=true|false`
- `-Pnative.<module>.buildLinuxOnWindows=true|false`

Examples:

```text
-Pnative.my_math.build=true
-Pnative.my_math.requireAll=true
-Pnative.my_math.buildLinuxOnWindows=true
```

`requireAll=true` makes the build expect every classifier listed in `native.properties`, either
from local builds or from prebuilts.

## Legacy DFC compatibility

The existing `dfc` module still supports the older `dfc.*` properties for compatibility:

- `-Pdfc.buildNatives`
- `-Pdfc.requireAllNatives`
- `-Pdfc.buildLinuxOnWindows`
- `-Pdfc.cmake`
- `-Pdfc.cmakeGenerator`
- `-Pdfc.wslDistribution`
- `-Pdfc.linuxJavaHome`

New modules should prefer the generic `native.*` properties.

## Typical workflow for a new module

1. Create `common/natives/<module>/`
2. Add `CMakeLists.txt`
3. Add native sources, usually under `c/`
4. Add `native.properties`
5. Optionally add prebuilts under `prebuilts/<platform>/`
6. Run:

```text
.\gradlew.bat :common:assembleBundledNatives
```

Or just run:

```text
.\gradlew.bat assemble
```

## Notes

- Multiple modules share the same packaged `natives/<platform>/` directory, so library filenames
  must be unique.
- Host-platform builds are automatic for discovered modules that support the current platform.
- If a module needs Linux artifacts from Windows, enable WSL builds or provide Linux prebuilts.
