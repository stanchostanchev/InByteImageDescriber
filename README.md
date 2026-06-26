# InByte Image Describer

Android app that lets you select an image, then uses a local GGUF model (LLaVA-compatible) running entirely on-device to describe it — no internet connection required.

## Architecture

- **UI**: Jetpack Compose + Material 3, chat-style interface
- **DI**: Hilt
- **Inference**: llama.cpp via JNI/NDK (CMake), supporting multimodal LLaVA models
- **Image loading**: Coil

## Setup

### 1. Clone llama.cpp

```bash
git clone https://github.com/ggerganov/llama.cpp \
    app/src/main/cpp/llama.cpp
```

### 2. Requirements

- Android Studio Hedgehog or newer
- NDK r26+ (install via SDK Manager → SDK Tools → NDK)
- CMake 3.22.1+
- A device or emulator with arm64-v8a or x86_64 ABI (minSdk 26)

### 3. Download a LLaVA GGUF model

You need **two files** for image description:

| File | Example | Size |
|------|---------|------|
| LLM (main model) | `llava-v1.6-mistral-7b.Q4_K_M.gguf` | ~4 GB |
| Vision projector | `mmproj-model-f16.gguf` | ~600 MB |

Good sources:
- [llava-v1.6-mistral-7b-gguf](https://huggingface.co/cjpais/llava-1.6-mistral-7b-gguf) on HuggingFace
- [moondream2-gguf](https://huggingface.co/vikhyatk/moondream2) (smaller, ~1.7 GB total)

Push the files to your device:
```bash
adb push llava-v1.6-mistral-7b.Q4_K_M.gguf /sdcard/models/
adb push mmproj-model-f16.gguf             /sdcard/models/
```

### 4. Build & run

Open the project in Android Studio and click **Run**.

### 5. In-app setup

On first launch, a dialog asks for:
- **LLM model path**: `/sdcard/models/llava-v1.6-mistral-7b.Q4_K_M.gguf`
- **Vision projector path**: `/sdcard/models/mmproj-model-f16.gguf`

Tap **Load Model**, wait for the model to initialise (~10–30 s depending on device), then:

1. Tap the **photo icon** to pick an image from your gallery
2. Optionally type a question (e.g. *"What is in this image?"*)
3. Tap **Send** — the description streams in token by token

## Tips

- First inference after loading is slower (model warm-up).
- Use Q4_K_M quantisation for the best speed/quality balance on mobile.
- If you only want text-only chat (no images), leave the vision projector path blank and skip the image attachment.
- `largeHeap="true"` is set in the manifest; ensure the device has ≥6 GB RAM for 7B models.

## Project structure

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt          # Builds llama.cpp + LLaVA JNI lib
│   └── llm_inference.cpp       # JNI bridge (load model, stream tokens)
└── kotlin/com/inbyte/imagedescriber/
    ├── inference/LlamaEngine.kt # Kotlin wrapper with Flow-based streaming
    ├── model/ChatMessage.kt
    ├── ui/chat/
    │   ├── ChatScreen.kt        # Full chat UI in Compose
    │   ├── ChatViewModel.kt
    │   └── ChatUiState.kt
    └── di/AppModule.kt
```
