cmake_minimum_required(VERSION 3.25)
project(DensityOperation LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(WIN32 OR MINGW)

    find_path(JAVA_INCLUDE_PATH jni.h
            PATHS $ENV{JAVA_HOME}/include /usr/lib/jvm/default/include
    )

    find_path(JAVA_INCLUDE_PATH2 jni_md.h
            PATHS $ENV{JAVA_HOME}/include/win32 /usr/lib/jvm/default/include/linux
    )

    if(JAVA_INCLUDE_PATH AND JAVA_INCLUDE_PATH2)
        message(STATUS "JNI headers found for Cross-Compilation")
        include_directories(${JAVA_INCLUDE_PATH} ${JAVA_INCLUDE_PATH2})
    else()
        message(FATAL_ERROR "JNI headers NOT found. Please set JAVA_HOME or copy headers to project.")
    endif()
else()
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
endif()

add_library(DensityOperation SHARED library.cpp
        src/structs/GenerationStructs.h
        src/NoiseGenerator.cpp
        src/NoiseGenerator.h
        src/structs/noise/Noises.h)

target_compile_options(DensityOperation PRIVATE
        $<$<OR:$<CXX_COMPILER_ID:GNU>,$<CXX_COMPILER_ID:Clang>>:-O3 -march=native -ffast-math -fno-plt>
        $<$<CXX_COMPILER_ID:MSVC>:/Ox /fp:fast /arch:AVX2>
)

if(UNIX AND NOT MINGW)
    target_link_options(DensityOperation PRIVATE "-static-libstdc++" "-static-libgcc")
    target_compile_options(DensityOperation PRIVATE "-fvisibility=hidden")
elseif(MINGW)
    target_link_options(DensityOperation PRIVATE "-static" "-static-libstdc++" "-static-libgcc")
endif()

set(DIST_DIR "${CMAKE_SOURCE_DIR}/dist")

if(WIN32 OR MINGW)
    set_target_properties(DensityOperation PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY "${DIST_DIR}/windows"
            RUNTIME_OUTPUT_DIRECTORY "$ё{DIST_DIR}/windows"
    )
    set_target_properties(DensityOperation PROPERTIES PREFIX "")
else()
    set_target_properties(DensityOperation PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY "${DIST_DIR}/linux"
    )
endif()