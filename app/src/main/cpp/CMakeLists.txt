cmake_minimum_required(VERSION 3.22.1)
project("inbyte-imagedescriber")

set(CMAKE_C_STANDARD   11)
set(CMAKE_CXX_STANDARD 17)

# Always build native code with full optimization regardless of Gradle build type
add_compile_options(-O3 -fno-finite-math-only)
add_link_options(-O3)

# ── llama.cpp ─────────────────────────────────────────────────────────────────
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
set(MTMD_DIR      "${LLAMA_CPP_DIR}/tools/mtmd")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_CPP_DIR}.")
endif()

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)

# ── ARM / Android optimisations ───────────────────────────────────────────────
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENMP      ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI ON  CACHE BOOL "" FORCE)   # ARM-optimised GEMM via KleidiAI
else()
    set(GGML_OPENMP      OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

# Android bionic does not expose posix_madvise / POSIX_MADV_*
if(ANDROID)
    add_compile_definitions(
        posix_madvise=madvise
        POSIX_MADV_WILLNEED=MADV_WILLNEED
        POSIX_MADV_RANDOM=MADV_RANDOM
        POSIX_MADV_SEQUENTIAL=MADV_SEQUENTIAL
        POSIX_MADV_DONTNEED=MADV_DONTNEED
        POSIX_MADV_NORMAL=MADV_NORMAL
    )
endif()

add_subdirectory(${LLAMA_CPP_DIR} llama_cpp_build EXCLUDE_FROM_ALL)

# ── mtmd — compile sources directly (avoids dragging in desktop CLI tools) ────
file(GLOB MTMD_MODEL_SOURCES "${MTMD_DIR}/models/*.cpp")

add_library(mtmd STATIC
    ${MTMD_DIR}/mtmd.cpp
    ${MTMD_DIR}/mtmd-image.cpp
    ${MTMD_DIR}/mtmd-helper.cpp
    ${MTMD_DIR}/mtmd-audio.cpp
    ${MTMD_DIR}/clip.cpp
    ${MTMD_MODEL_SOURCES}
)

target_include_directories(mtmd PUBLIC  ${MTMD_DIR})
target_include_directories(mtmd PRIVATE
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/vendor
    ${MTMD_DIR}/models
)
target_link_libraries   (mtmd PUBLIC ggml llama)
target_compile_features (mtmd PRIVATE cxx_std_17)

# ── JNI bridge ────────────────────────────────────────────────────────────────
add_library(inbyte-inference SHARED llm_inference.cpp)

target_include_directories(inbyte-inference PRIVATE
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/vendor
    ${MTMD_DIR}
)

target_link_libraries(inbyte-inference
    llama
    mtmd
    android
    log
)

target_compile_features(inbyte-inference PRIVATE cxx_std_17)
target_compile_options(inbyte-inference PRIVATE -O3 -fno-finite-math-only)
