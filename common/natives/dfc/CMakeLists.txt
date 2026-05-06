cmake_minimum_required(VERSION 3.16)
project(dfc_native C)

if(NOT DEFINED JAVA_HOME)
  set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
if(NOT JAVA_HOME)
  message(FATAL_ERROR "JAVA_HOME not set (pass -DJAVA_HOME= from Gradle using java.home)")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(MSVC)
  add_compile_options(/O2 /W3)
else()
  add_compile_options(-O3 -Wall -Wextra -Wno-unused-parameter)
endif()

set(DFC_SOURCES
  c/dfc_jni.c
  c/wrap_axis.c
  c/improved_noise.c
  c/normal_noise_stack.c
  c/improved_noise_avx2.c
  c/blended_noise.c
  c/slab_inner_vm.c
)

add_library(dfc_native SHARED ${DFC_SOURCES})

if(MSVC)
  target_compile_definitions(dfc_native PRIVATE DFC_JNI_EXPORTS)
  set_target_properties(dfc_native PROPERTIES PREFIX "")
else()
  set_target_properties(dfc_native PROPERTIES PREFIX "lib")
endif()

if(CMAKE_SYSTEM_PROCESSOR MATCHES "AMD64|x86_64|x64")
  if(MSVC)
    set_source_files_properties(c/improved_noise_avx2.c PROPERTIES COMPILE_OPTIONS "/arch:AVX2")
  else()
    set_source_files_properties(c/improved_noise_avx2.c PROPERTIES COMPILE_OPTIONS "-mavx2;-mfma")
  endif()
endif()

target_include_directories(dfc_native PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/c
  ${JAVA_HOME}/include
)

if(WIN32)
  target_include_directories(dfc_native PRIVATE ${JAVA_HOME}/include/win32)
elseif(APPLE)
  target_include_directories(dfc_native PRIVATE ${JAVA_HOME}/include/darwin)
else()
  target_include_directories(dfc_native PRIVATE ${JAVA_HOME}/include/linux)
endif()
