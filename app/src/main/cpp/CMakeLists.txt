# On-device EN -> ES translation: slimt (Bergamot/Marian inference, GPL-2.0-or-later)
# built from the app/src/main/cpp/slimt submodule, exposed to Kotlin through JNI.
cmake_minimum_required(VERSION 3.22.1)
project(twinbooks_translate CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# sentencepiece still declares an ancient cmake_minimum_required
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

set(SLIMT_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/slimt)

set(WITH_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(BUILD_JNI OFF CACHE BOOL "" FORCE)
set(USE_BUILTIN_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  # Vectorized softmax/exp paths; NEON is mandatory on aarch64
  set(USE_NEON ON CACHE BOOL "" FORCE)
endif()

# - fp-contract=off: same rounding on every ABI (FMA fusion flips low-confidence
#   tokens on ARM only); no -ffast-math for the same reason (see slimt-sys).
# - sections + gc-sections + hidden visibility: drop everything JNI doesn't reach.
# - cstdint (C++ only): sentencepiece uses uint32_t without including it.
set(COMMON_FLAGS "-ffp-contract=off -ffunction-sections -fdata-sections -fvisibility=hidden -Wno-unused-command-line-argument")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${COMMON_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${COMMON_FLAGS} -fvisibility-inlines-hidden -include cstdint")
# Reproducible builds: no absolute build paths in the binary
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

# slimt's sources include "slimt/*.hh" relative to the top-level source dir
include_directories(${SLIMT_SOURCE_DIR} ${CMAKE_BINARY_DIR}/slimt)
add_subdirectory(${SLIMT_SOURCE_DIR} slimt EXCLUDE_FROM_ALL)

add_library(twinbooks_translate SHARED slimt_engine.cc slimt_jni.cc)
target_link_libraries(twinbooks_translate PRIVATE slimt-static log)
target_link_options(twinbooks_translate PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
