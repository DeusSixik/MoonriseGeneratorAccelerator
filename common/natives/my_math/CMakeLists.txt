cmake_minimum_required(VERSION 3.16)
project(my_math C)

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

add_library(my_math SHARED
  c/my_math_jni.c
)

if(MSVC)
  set_target_properties(my_math PROPERTIES PREFIX "")
else()
  set_target_properties(my_math PROPERTIES PREFIX "lib")
endif()

target_include_directories(my_math PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/c
  ${JAVA_HOME}/include
)

if(WIN32)
  target_include_directories(my_math PRIVATE ${JAVA_HOME}/include/win32)
elseif(APPLE)
  target_include_directories(my_math PRIVATE ${JAVA_HOME}/include/darwin)
else()
  target_include_directories(my_math PRIVATE ${JAVA_HOME}/include/linux)
endif()
