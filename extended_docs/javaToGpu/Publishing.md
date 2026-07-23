# Publishing Guide

This guide describes how to publish JavaToGpu Maven artifacts to Maven Central.

Use this only for release work. If you are consuming JavaToGpu from another project, copy the dependency block from [Getting Started](Getting-Started.md) instead.

The safe release shape is: validate, stage locally, inspect generated POM/artifacts, then publish all JavaToGpu modules with the same version.

## Artifact Coordinates

All published artifacts share the same release version:

```text
groupId: io.github.deussixik
version: 0.1.0-alpha.4
```

Published modules:

```text
:processor            -> io.github.deussixik:javatogpu
:ir-validation        -> io.github.deussixik:javatogpu-ir-validation
:ir-optimizer         -> io.github.deussixik:javatogpu-ir-optimizer
:ir-vendor-optimizer  -> io.github.deussixik:javatogpu-ir-vendor-optimizer
```

`examples-app` and `test-app` are consumer/demo applications and are not published.

## What Is Published

Each published module uses the `mavenJava` publication and includes:

- compiled jar
- sources jar
- javadoc jar
- generated Maven POM metadata
- PGP signatures for release publishing when signing credentials are present

Gradle module metadata is disabled for publication so build-host-specific LWJGL native classifiers are not published as universal metadata. Consumers should add their own LWJGL native classifier for their operating system when they need runtime OpenCL execution.

## Release Dependency Baseline

The OpenCL runtime depends on the published Packager release:

```text
io.github.deussixik:packager:1.0.0-alpha.1
```

Release publishing is guarded by `validateMavenCentralReleaseReadiness`, which fails if a generated POM contains snapshot dependencies or JitPack branch dependencies.

## Secrets

Never commit Maven Central credentials or signing keys.

Put local release secrets in:

```text
~/.gradle/gradle.properties
```

Recommended properties:

```properties
mavenCentralUsername=<central-token-username>
mavenCentralPassword=<central-token-password>
signingInMemoryKey=<ascii-armored-private-key>
signingInMemoryKeyPassword=<private-key-password>
```

Equivalent environment variables are also supported:

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
SIGNING_IN_MEMORY_KEY
SIGNING_IN_MEMORY_KEY_PASSWORD
```

The local `gradle.properties` file in this repository root is ignored by Git as an extra safety net, but the preferred location is still `~/.gradle/gradle.properties`.

## Aggregate Gradle Tasks

The root build exposes convenience tasks for the full artifact set:

```powershell
.\gradlew.bat printJavaToGpuPublicationCoordinates --console=plain
.\gradlew.bat validateJavaToGpuMavenCentralReleaseReadiness --console=plain
.\gradlew.bat publishJavaToGpuToLocalStaging --console=plain
.\gradlew.bat publishJavaToGpuSnapshotsToCentral -Pjavatogpu.version=0.1.0-SNAPSHOT --console=plain
.\gradlew.bat publishJavaToGpuReleasesToCentral -Pjavatogpu.version=0.1.0-alpha.4 --console=plain
```

PowerShell users can quote the Gradle property if the shell splits `-P` incorrectly:

```powershell
.\gradlew.bat "publishJavaToGpuReleasesToCentral" "-Pjavatogpu.version=0.1.0-alpha.4" --console=plain
```

## Per-Module Commands

Use per-module tasks when publishing or inspecting a single artifact:

```powershell
.\gradlew.bat :processor:publishMavenJavaPublicationToLocalStagingRepository --console=plain
.\gradlew.bat :ir-validation:publishMavenJavaPublicationToLocalStagingRepository --console=plain
.\gradlew.bat :ir-optimizer:publishMavenJavaPublicationToLocalStagingRepository --console=plain
.\gradlew.bat :ir-vendor-optimizer:publishMavenJavaPublicationToLocalStagingRepository --console=plain
```

Remote release publishing uses the matching `CentralReleasesRepository` task in each module. Snapshot publishing uses the matching `CentralSnapshotsRepository` task.

## Local Staging Check

Before publishing remotely, build the local staging repositories:

```powershell
.\gradlew.bat publishJavaToGpuToLocalStaging --console=plain
```

Output is written under each module's build directory:

```text
processor/build/maven-staging/
ir-validation/build/maven-staging/
ir-optimizer/build/maven-staging/
ir-vendor-optimizer/build/maven-staging/
```

Inspect the generated POMs before release:

```text
processor/build/publications/mavenJava/pom-default.xml
ir-validation/build/publications/mavenJava/pom-default.xml
ir-optimizer/build/publications/mavenJava/pom-default.xml
ir-vendor-optimizer/build/publications/mavenJava/pom-default.xml
```

## Recommended Release Flow

1. Run normal tests for published modules:

```powershell
.\gradlew.bat :processor:test :ir-validation:test :ir-optimizer:test :ir-vendor-optimizer:test --console=plain
```

2. Run the OpenCL operational routine on the validated GPU machine:

```powershell
.\gradlew.bat :processor:openClOperationalRoutine --rerun-tasks --console=plain
```

3. Run Maven Central release-readiness guards:

```powershell
.\gradlew.bat validateJavaToGpuMavenCentralReleaseReadiness --console=plain
```

4. Build local Maven staging:

```powershell
.\gradlew.bat publishJavaToGpuToLocalStaging --console=plain
```

5. Inspect generated POM files and staged artifacts.

6. Publish the release:

```powershell
.\gradlew.bat publishJavaToGpuReleasesToCentral -Pjavatogpu.version=0.1.0-alpha.4 --console=plain
```

After upload, complete the release from the Maven Central / Sonatype portal if the deployment lands in a staging flow that requires manual close/release. Release all JavaToGpu artifacts with the same version.

## Consumer Example

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.deussixik:javatogpu:0.1.0-alpha.4'
    annotationProcessor 'io.github.deussixik:javatogpu:0.1.0-alpha.4'

    annotationProcessor 'io.github.deussixik:javatogpu-ir-validation:0.1.0-alpha.4' // optional strict IR checks

    // Optional optimizer proposal providers.
    implementation 'io.github.deussixik:javatogpu-ir-optimizer:0.1.0-alpha.4'
    implementation 'io.github.deussixik:javatogpu-ir-vendor-optimizer:0.1.0-alpha.4'

    runtimeOnly 'org.lwjgl:lwjgl::natives-windows'
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += '-Ajavatogpu.irValidation=diagnostic'
    options.compilerArgs += '-Ajavatogpu.irValidationDiagnostics=summary'
    options.compilerArgs += '-Ajavatogpu.irValidationReport=reports/javatogpu-ir-validation.properties'
}
```

Use the correct LWJGL native classifier for the consumer platform, such as `natives-windows`, `natives-linux`, or `natives-macos`.
