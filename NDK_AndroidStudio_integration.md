# NDK & Android Studio Integration — InByte Image Describer

## Host Machine

| Item | Value |
|---|---|
| OS | macOS (darwin-x86_64) |
| Android SDK root | `~/Library/Android/sdk` |
| `ANDROID_HOME` | `~/Library/Android/sdk` (set at build time via env var; no `local.properties` checked in) |

---

## Android SDK

| Component | Version |
|---|---|
| Compile SDK | **35** (Android 15) |
| Target SDK | **35** |
| Min SDK | **26** (Android 8.0 Oreo) |
| Build Tools | 36.0.0 (latest installed) |
| Platform | android-35 |

Installed platform levels available: 23, 26–37.

---

## NDK (Native Development Kit)

### Active version used by this project

**NDK 27.0.12077973**

This is automatically selected by the Android Gradle Plugin (AGP 8.7.3) as the default NDK when no explicit `ndkVersion` is pinned in `app/build.gradle.kts`. It is the version that Gradle resolved at the time the project was first synced/built.

### Installation path

```
~/Library/Android/sdk/ndk/27.0.12077973/
```

### Toolchain

The NDK ships its own Clang toolchain. For this project's ARM64 target:

```
~/Library/Android/sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/
```

- **Compiler:** Clang 18 (bundled with NDK 27)
- **OpenMP runtime:** `lib/clang/18/lib/linux/aarch64/libomp.so` — packaged into the APK automatically by AGP when OpenMP is used

### Other NDK versions installed (not used by this project)

```
21.4.7075529
23.1.7779620
25.1.8937393
26.1.10909125
27.2.12479018
28.0.13004108
28.2.13676358
29.0.13846066
```

To pin a specific NDK version, add to `app/build.gradle.kts`:

```kotlin
android {
    ndkVersion = "27.0.12077973"
}
```

---

## CMake

AGP uses CMake to drive the native build. Three versions are installed:

| Version | Path |
|---|---|
| 3.18.1 | `~/Library/Android/sdk/cmake/3.18.1/` |
| **3.22.1** | `~/Library/Android/sdk/cmake/3.22.1/` ← **used by this project** |
| 3.31.5 | `~/Library/Android/sdk/cmake/3.31.5/` |

### How the version is selected

In `app/build.gradle.kts`:

```kotlin
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
```

The `version` field tells AGP which CMake installation under `$ANDROID_HOME/cmake/` to invoke. CMake 3.22.1 is also the minimum version declared at the top of `CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
```

---

## Android Gradle Plugin & Build Tools

| Tool | Version |
|---|---|
| Android Gradle Plugin (AGP) | **8.7.3** |
| Gradle wrapper | **8.9** |
| Kotlin | **2.0.21** |
| KSP (Kotlin Symbol Processing) | **2.0.21-1.0.27** |

---

## Project Native Build Configuration

### `app/build.gradle.kts` — native section

```kotlin
defaultConfig {
    ndk {
        abiFilters += listOf("arm64-v8a", "x86_64")
    }
    externalNativeBuild {
        cmake {
            cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            arguments += listOf(
                "-DLLAMA_BUILD_TESTS=OFF",
                "-DLLAMA_BUILD_EXAMPLES=OFF",
                "-DLLAMA_BUILD_SERVER=OFF",
                "-DGGML_METAL=OFF",
                "-DGGML_VULKAN=OFF",
            )
        }
    }
}

externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
```

- **ABI filters:** only `arm64-v8a` and `x86_64` are built — no 32-bit artefacts, keeping the APK smaller.
- **Metal / Vulkan:** both disabled — CPU-only inference.

### `app/src/main/cpp/CMakeLists.txt` — key decisions

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("inbyte-imagedescriber")

set(CMAKE_C_STANDARD   11)
set(CMAKE_CXX_STANDARD 17)

# Force -O3 globally so llama.cpp, ggml, mtmd and KleidiAI are all
# fully optimised even in a Gradle debug build (which defaults to -O0).
add_compile_options(-O3 -fno-finite-math-only)
add_link_options(-O3)
```

> **Critical:** Gradle's debug build type passes no `-O` flag to CMake, so all native code compiles at `-O0` by default. Adding `add_compile_options(-O3)` globally fixes this.  
> `-ffast-math` is intentionally avoided — ggml requires non-finite math support (`-fno-finite-math-only`) and will emit a compile-time error otherwise.

```cmake
# ARM-specific optimisations (arm64-v8a only)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENMP       ON CACHE BOOL "" FORCE)   # multi-thread via libomp
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)   # ARM-optimised GEMM kernels
endif()
```

- **KleidiAI** — ARM's open-source GEMM kernel library, bundled inside llama.cpp. Activated via `GGML_CPU_KLEIDIAI=ON`. Provides `DOTPROD` / `I8MM` / `SVE` optimised matrix multiply on Snapdragon and Tensor SoCs.
- **OpenMP** — uses `libomp` from the NDK Clang 18 toolchain. AGP automatically packages `libomp.so` into the APK.

```cmake
# Android bionic libc does not expose posix_madvise / POSIX_MADV_*
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
```

llama.cpp uses `posix_madvise` which is not part of Android's Bionic libc. These `#define` remaps redirect calls to the equivalent `madvise` syscall that Bionic does expose.

### Library targets built

| Target | Type | Purpose |
|---|---|---|
| `ggml` | shared | Low-level tensor ops, KleidiAI, OpenMP — built by llama.cpp's CMake |
| `llama` | shared | High-level LLM inference API |
| `mtmd` | static | Multimodal (vision) API — compiled directly from `tools/mtmd/` source files to avoid pulling in desktop CLI tools (`arg.h`) |
| `inbyte-inference` | shared | JNI bridge — the `.so` loaded by `System.loadLibrary("inbyte-inference")` |

`mtmd` links `ggml` and `llama`. `inbyte-inference` links `llama`, `mtmd`, `android`, and `log`.

### Output artefacts

AGP strips and packages the `.so` files into the APK under:

```
lib/arm64-v8a/
    libggml.so
    libllama.so
    libinbyte-inference.so   ← loaded at runtime
    libomp.so                ← OpenMP runtime (from NDK Clang 18)
lib/x86_64/
    (same set for emulator)
```

---

## Android Studio Setup

### Recommended Android Studio version

**Android Studio Ladybug (2024.2.1)** or newer — ships with AGP 8.x support and the NDK/CMake SDK manager integration.

### Installing NDK & CMake via Android Studio

1. Open **Android Studio → Settings → Appearance & Behavior → System Settings → Android SDK**
2. Select the **SDK Tools** tab
3. Check **NDK (Side by side)** → select version **27.0.12077973**
4. Check **CMake** → select version **3.22.1**
5. Click **Apply / OK** — SDK Manager downloads to `~/Library/Android/sdk/ndk/` and `~/Library/Android/sdk/cmake/`

### Syncing the project

After cloning / unzipping the project:

1. Open the project root in Android Studio (the folder containing `settings.gradle.kts`)
2. Android Studio detects `externalNativeBuild.cmake.path` in `app/build.gradle.kts` and triggers a CMake configure pass automatically on Gradle sync
3. The `.cxx/` directory is created under `app/` — this is the CMake build directory (excluded from version control)
4. Build via **Build → Make Project** or `./gradlew assembleDebug` from terminal

### Environment variable

If building from the terminal without Android Studio, set:

```bash
export ANDROID_HOME=~/Library/Android/sdk
```

No `local.properties` file is required as long as `ANDROID_HOME` is set. If `local.properties` is present it takes precedence:

```properties
sdk.dir=/Users/<username>/Library/Android/sdk
```

---

## Key Versions Summary

| Component | Version |
|---|---|
| NDK | **27.0.12077973** (Clang 18) |
| CMake | **3.22.1** |
| Android SDK (compile/target) | **35** |
| Android SDK (min) | **26** |
| AGP | **8.7.3** |
| Gradle | **8.9** |
| Kotlin | **2.0.21** |
| KSP | **2.0.21-1.0.27** |
| Hilt | **2.52** |
| Coil | **2.7.0** |
| llama.cpp | commit `6eab471` (GGML 0.15.1) |

---

## Build Commands

`local.properties` is present in the project root, so no `ANDROID_HOME` environment variable is needed. All commands are run from the project root (`InByteImageDescriber/`).

### Build

```bash
./gradlew assembleDebug
```

Gradle reads the SDK path from `local.properties`. Produces:
`app/build/outputs/apk/debug/app-debug.apk`

### Install

```bash
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

- `-s <SERIAL>` — targets a specific device when multiple are connected
- `-r` — reinstall keeping app data

### Launch

```bash
adb -s <SERIAL> shell am start -n com.inbyte.imagedescriber/.MainActivity
```

### Device serials

| Serial | Device |
|---|---|
| `RZCXA12ZFLR` | Samsung Galaxy Z Fold 6 |
| `2C061FDH2007JE` | Pixel 7 |
| `R52WB0BYTJN` | Samsung SM-X716B (tablet) |

List connected devices:

```bash
adb devices
```

### Logs

```bash
# Dump current log buffer and exit
adb -s <SERIAL> logcat -d -s InByteInference

# Live stream during generation
adb -s <SERIAL> logcat -s InByteInference
```

### Native quantization (one-time, macOS)

```bash
# Build llama-quantize for macOS
~/Library/Android/sdk/cmake/3.31.5/bin/cmake -B /tmp/llama_mac_build2 \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  app/src/main/cpp/llama.cpp
~/Library/Android/sdk/cmake/3.31.5/bin/cmake --build /tmp/llama_mac_build2 \
  --target llama-quantize -j$(sysctl -n hw.logicalcpu)

# Quantize F16 → Q4_K_M
/tmp/llama_mac_build2/bin/llama-quantize \
  /tmp/SmolVLM-256M-f16.gguf \
  app/src/main/assets/models/SmolVLM-256M-Instruct-Q4_K_M.gguf \
  Q4_K_M
```

### Archive

```bash
cd /Users/stanchostanchev/____ai/_______test && zip -r ___inbyte_image_describer_WORKING_1.zip InByteImageDescriber \
  --exclude "InByteImageDescriber/app/src/main/cpp/llama.cpp/.git/*" \
  --exclude "InByteImageDescriber/.gradle/*" \
  --exclude "InByteImageDescriber/app/.cxx/*" \
  --exclude "InByteImageDescriber/app/build/*" \
  --exclude "InByteImageDescriber/build/*"
```
