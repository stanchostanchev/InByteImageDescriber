# InByte Image Describer — Application Architecture

## Overview

InByte Image Describer is a single-screen Android application that performs **on-device multimodal AI inference**. The user selects or captures an image, optionally types a prompt, and the app generates a natural-language description entirely on the device — no network requests, no cloud API.

The architecture follows **MVVM (Model-View-ViewModel)** with a unidirectional data flow, implemented using Jetpack Compose for the UI and Hilt for dependency injection.

---

## Layer Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│  ChatScreen.kt  (Jetpack Compose)                            │
│  ChatUiState.kt (immutable state snapshot)                   │
│  ChatViewModel.kt (state holder, coroutine scope)            │
├─────────────────────────────────────────────────────────────┤
│                     Inference Layer                          │
│  LlamaEngine.kt   (Kotlin JNI wrapper, Flow streaming)       │
│  ModelSetupHelper.kt (asset → internal storage copy)         │
├─────────────────────────────────────────────────────────────┤
│                      Native Layer (C++ / NDK)                │
│  llm_inference.cpp  (JNI bridge)                             │
│  mtmd              (multimodal tokenisation & image eval)    │
│  llama.cpp / ggml  (transformer inference engine)            │
│  KleidiAI          (ARM-optimised GEMM kernels)              │
├─────────────────────────────────────────────────────────────┤
│                       Model Assets                           │
│  SmolVLM-256M-Instruct-Q4_K_M.gguf   (language model)       │
│  mmproj-SmolVLM-256M-Instruct-Q8_0.gguf (vision projector)  │
└─────────────────────────────────────────────────────────────┘
```

---

## Package Structure

```
com.inbyte.imagedescriber/
├── InByteApplication.kt        — Hilt application entry point
├── MainActivity.kt             — single Activity, hosts Compose tree
├── di/
│   └── AppModule.kt            — Hilt singleton bindings
├── model/
│   └── ChatMessage.kt          — domain data class + MessageRole enum
├── inference/
│   ├── LlamaEngine.kt          — Kotlin JNI wrapper + Flow streaming
│   └── ModelSetupHelper.kt     — copies assets to internal storage
└── ui/
    ├── chat/
    │   ├── ChatScreen.kt        — Compose UI (screen + composables)
    │   ├── ChatViewModel.kt     — ViewModel: state + business logic
    │   └── ChatUiState.kt       — immutable UI state data class
    └── theme/
        └── Theme.kt             — Material3 theme
```

---

## Component Details

### `InByteApplication`

Annotated with `@HiltAndroidApp` — triggers Hilt code generation and installs the application-scoped DI component. No other logic.

---

### `MainActivity`

- Single `ComponentActivity`, annotated `@AndroidEntryPoint` for Hilt injection.
- Calls `enableEdgeToEdge()` for full-bleed rendering.
- Sets Compose content: `InByteTheme { ChatScreen() }`.
- The app has **one Activity, one screen** — no navigation graph needed.

---

### DI — `AppModule`

```kotlin
@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideLlamaEngine(): LlamaEngine = LlamaEngine()
}
```

`LlamaEngine` is a **process-scoped singleton** — the native model is loaded once and reused for every inference call. Hilt injects it into `ChatViewModel` via constructor injection.

---

### Domain Model — `ChatMessage`

```kotlin
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,           // USER | ASSISTANT
    val text: String,
    val imageUri: Uri? = null,       // non-null for messages with an image
    val isStreaming: Boolean = false, // true while tokens are being generated
)
```

A simple immutable value object. The entire conversation history is held as `List<ChatMessage>` inside `ChatUiState`.

---

### `ChatUiState`

```kotlin
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val pendingImageUri: Uri? = null,   // image selected but not yet sent
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val isModelLoaded: Boolean = false,
    val loadingMessage: String? = "Preparing model files…",
    val errorMessage: String? = null,
)
```

Single immutable snapshot of everything the UI needs. The ViewModel publishes it as a `StateFlow`; the screen collects it and re-composes reactively.

---

### `ChatViewModel`

The core orchestrator. Responsibilities:

1. **Model loading** — on `init`, calls `ModelSetupHelper.prepareModels()` then `LlamaEngine.loadModel()`. Progress messages are emitted to `loadingMessage`.
2. **Message sending** — appends a user `ChatMessage` (with optional image URI) and a streaming assistant `ChatMessage`, then launches a coroutine to collect the inference `Flow`.
3. **Token streaming** — each token from `LlamaEngine` is appended to the assistant message's `text` in-place via `StateFlow.update`.
4. **Lifecycle** — calls `LlamaEngine.free()` in `onCleared()` to release native memory when the ViewModel is destroyed.

```
User taps Send
    │
    ▼
ChatViewModel.sendMessage()
    │  appends USER + ASSISTANT (empty, isStreaming=true) to messages
    │
    ▼
LlamaEngine.describeImage() / .generate()
    │  returns Flow<String>
    │
    ▼
flow.collect { token →
    update assistant message text += token
}
    │
    ▼
finally: isStreaming=false, isGenerating=false
```

Thread model: model loading runs on `Dispatchers.IO`; inference runs on `Dispatchers.Default` (via `flowOn` in `LlamaEngine`).

---

### `ModelSetupHelper`

llama.cpp requires **file paths** — it cannot read from Android asset streams. On first launch, `ModelSetupHelper` copies both GGUF files from `assets/models/` to `context.filesDir/models/`:

```
assets/models/SmolVLM-256M-Instruct-Q4_K_M.gguf   → filesDir/models/
assets/models/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf → filesDir/models/
```

Subsequent launches detect the files already exist and skip the copy. The absolute file paths are returned to `ChatViewModel` for passing to `LlamaEngine.loadModel()`.

---

### `LlamaEngine`

Kotlin wrapper around the native JNI library. It is a `@Singleton`.

```kotlin
System.loadLibrary("inbyte-inference")  // loads libinbyte-inference.so
```

**Key methods:**

| Method | Description |
|---|---|
| `loadModel(modelPath, clipPath, contextSize, threads)` | Calls `nativeLoadModel()`, stores the returned native pointer |
| `describeImage(context, imageUri, prompt, maxTokens)` | Reads & resizes the image to max 512×512, calls `nativeDescribeImage()`, streams tokens via `callbackFlow` |
| `generate(prompt, maxTokens)` | Text-only inference, delegates to `nativeDescribeImage()` with null image bytes |
| `free()` | Calls `nativeFree()` to release native context, model, sampler, and mtmd context |

Image bytes are resized on the Kotlin side before being passed to JNI:

```kotlin
// Resize to max 512×512 to prevent multi-tile vision encoding (9× slower)
val imageBytes = loadAndResizeImage(context, imageUri, maxSize = 512)
```

Inference is streamed via a `callbackFlow` running on `Dispatchers.Default`, so the UI thread is never blocked.

---

### Native Layer — `llm_inference.cpp`

The JNI bridge. Manages the full lifecycle of native objects:

```
InferenceContext {
    llama_model*   model    // language model weights
    llama_context* ctx      // KV cache + inference state
    mtmd_context*  mtmd     // vision encoder + multimodal tokeniser
    llama_sampler* sampler  // top-k / top-p / temperature chain
    llama_vocab*   vocab    // token ↔ string mapping
}
```

**Inference flow for an image + prompt:**

```
1. llama_memory_clear()          — reset KV cache for new conversation turn
2. Build prompt string:
   "<|im_start|>User:<__media__>\n{prompt}<|im_end|>\n<|im_start|>Assistant:\n"
3. mtmd_helper_bitmap_init_from_buf()  — decode JPEG bytes → bitmap
4. mtmd_tokenize()               — split prompt into text + image chunks
5. mtmd_helper_eval_chunks()     — run vision encoder + LLM prefill
6. loop: llama_sampler_sample()  — sample next token
          llama_token_to_piece() — decode to UTF-8 string fragment
          invokeCallback()       — call Kotlin lambda with token string
          llama_decode()         — feed token back as next input
          (repeat until EOS or maxTokens)
```

**Performance settings in use:**

| Setting | Value | Effect |
|---|---|---|
| `n_threads` / `n_threads_batch` | 4 | Performance cores only; avoids efficiency-core sync overhead |
| `flash_attn_type` | `LLAMA_FLASH_ATTN_TYPE_AUTO` | Enables flash attention where supported |
| `n_gpu_layers` | 0 | CPU-only inference |
| `GGML_CPU_KLEIDIAI` | ON | ARM DOTPROD/I8MM/SVE GEMM kernels |
| `GGML_OPENMP` | ON | Multi-threaded tensor ops via libomp |
| `-O3` global | ON | Full compiler optimisation (critical — debug build defaults to -O0) |

**Sampler chain:**

```
top-k (k=40) → top-p (p=0.9) → temperature (t=0.7) → dist (random seed)
```

---

### UI — `ChatScreen`

Built entirely in Jetpack Compose. Key composables:

| Composable | Role |
|---|---|
| `ChatScreen` | Root — hosts `Scaffold`, collects `uiState`, wires launchers |
| `ChatInputBar` | Bottom bar — gallery picker, camera button, text field, send button |
| `ChatBubble` | Single message row — image thumbnail + text card, streaming cursor |
| `PendingImagePreview` | Thumbnail of selected image before sending, with ✕ dismiss |
| `AssistantAvatar` / `UserAvatar` | Small circular avatars beside bubbles |
| `StreamingCursor` | Blinking `▌` shown while `isStreaming = true` |

**Loading overlay:** while `loadingMessage != null`, a semi-transparent overlay with a `CircularProgressIndicator` blocks the UI. It shows two lines — the current progress step and `"SmolVLM-256M · on-device inference"`.

**Camera integration:**

```kotlin
// 1. Request runtime CAMERA permission
rememberLauncherForActivityResult(RequestPermission()) { granted →
    if (granted) camera.launch(uri)
}
// 2. Create a FileProvider URI in cache/camera/
FileProvider.getUriForFile(context, "${packageName}.fileprovider", file)
// 3. Launch TakePicture contract
rememberLauncherForActivityResult(TakePicture()) { success →
    if (success) onImageSelect(cameraUri)
}
```

---

## Data Flow Diagram

```
[User]
  │  taps camera / gallery button
  ▼
[ChatScreen]  →  onImageSelected(uri)  →  [ChatViewModel]
                                               │ pendingImageUri = uri
  │  types prompt + taps Send
  ▼
[ChatScreen]  →  sendMessage()  →  [ChatViewModel]
                                       │
                                       ├─ append USER message  (uri + text)
                                       ├─ append ASSISTANT message (empty, streaming)
                                       │
                                       ▼
                               [LlamaEngine.describeImage()]
                                       │ resize image to 512×512
                                       │ pass bytes + prompt via JNI
                                       │
                                       ▼
                               [llm_inference.cpp]
                                       │ vision encode → prefill → sample
                                       │ invokeCallback(token) per token
                                       │
                                       ▼
                               Flow<String>  (Dispatchers.Default)
                                       │
                                       ▼
                               [ChatViewModel.collect]
                                       │ assistant.text += token  (StateFlow.update)
                                       │
                                       ▼
                               [ChatScreen recompose]
                                       │ new token appears in bubble
                                       ▼
                              [User sees streaming text]
```

---

## AI Model

| Property | Value |
|---|---|
| Model | SmolVLM-500M-Instruct |
| Language model | SmolLM2-500M (~2× parameters vs 256M variant) |
| Vision encoder | SigLIP (in mmproj GGUF) |
| LM quantisation | Q4_K_M (~289 MB) |
| Vision projector | Q8_0 (~104 MB) |
| Image tokens | 64 per image (single tile, 384×384 input to encoder) |
| Context size | 2048 tokens |
| Prompt format | ChatML (`<\|im_start\|>` / `<\|im_end\|>`) with `<__media__>` image marker |
| Runtime storage | `filesDir/models/` (copied from assets on first launch) |

### Model asset files

| File | Size | Source |
|---|---|---|
| `SmolVLM-500M-Instruct-Q4_K_M.gguf` | 289 MB | Quantized from F16 using `llama-quantize` |
| `mmproj-SmolVLM-500M-Instruct-Q8_0.gguf` | 104 MB | Downloaded directly from `ggml-org/SmolVLM-500M-Instruct-GGUF` |

### Model upgrade history

| Version | LM file | LM size | mmproj size | Reason for change |
|---|---|---|---|---|
| v1 | `SmolVLM-256M-Instruct-Q8_0.gguf` | 167 MB | 99 MB | Initial model — too slow (compiled at `-O0`) |
| v2 | `SmolVLM-256M-Instruct-Q4_K_M.gguf` | 119 MB | 99 MB | Quantized to Q4_K_M + `-O3` fix → fast |
| v3 (current) | `SmolVLM-500M-Instruct-Q4_K_M.gguf` | 289 MB | 104 MB | 2× more parameters → better description quality |

### Why no code changes were needed for the upgrade

SmolVLM-256M and SmolVLM-500M share the same:
- **Prompt format** — ChatML with `<__media__>` image marker
- **mtmd API calls** — `mtmd_tokenize`, `mtmd_helper_eval_chunks`, etc.
- **Vision encoder type** — SigLIP (same architecture, larger weights in 500M)
- **Image token count** — 64 tokens per image, single tile

Only `ModelSetupHelper.kt` (filenames) and the loading overlay label in `ChatScreen.kt` required updating.

---

## Model Quantization

### Why quantize?

The model was originally downloaded as **Q8_0** (8-bit, ~167MB). Running it was slow because it saturates memory bandwidth — reading 8 bits per weight on every matrix multiply. **Q4_K_M** halves that to ~4 bits per weight (~119MB), roughly doubling throughput.

### The problem: can't requantize from Q8_0

Running `llama-quantize` directly on the Q8_0 file fails:

```
requantizing from type q8_0 is disabled
```

llama.cpp only allows quantizing from **F16** (16-bit float), which is the lossless base format. So the F16 model had to be downloaded first.

### Step 1 — Download the F16 model

```bash
curl -L -o /tmp/SmolVLM-256M-f16.gguf \
  "https://huggingface.co/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/SmolVLM-256M-Instruct-f16.gguf"
```

This gives a 313MB F16 GGUF — all weights in full 16-bit float precision.

### Step 2 — Build `llama-quantize` for macOS

`llama-quantize` is a tool inside llama.cpp. It must be compiled natively for **macOS** (runs on the host machine, not the phone).

```bash
# Configure
~/Library/Android/sdk/cmake/3.31.5/bin/cmake \
  -B /tmp/llama_mac_build2 \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  app/src/main/cpp/llama.cpp

# Build just the quantize tool
~/Library/Android/sdk/cmake/3.31.5/bin/cmake \
  --build /tmp/llama_mac_build2 \
  --target llama-quantize \
  -j$(sysctl -n hw.logicalcpu)
```

The binary lands at `/tmp/llama_mac_build2/bin/llama-quantize`.

> The Android SDK's CMake 3.31.5 is used here — it's just CMake, it works fine for macOS native builds too.

### Step 3 — Quantize F16 → Q4_K_M

```bash
/tmp/llama_mac_build2/bin/llama-quantize \
  /tmp/SmolVLM-256M-f16.gguf \
  app/src/main/assets/models/SmolVLM-256M-Instruct-Q4_K_M.gguf \
  Q4_K_M
```

Output:
```
llama_model_quantize_impl: quant size  =   117.55 MiB (6.05 BPW)
llama_model_quantize_impl: WARNING: 181 of 273 tensor(s) required fallback quantization
llama_quantize: quantize time =   413.79 ms
```

- **Q4_K_M** — 4-bit quantization using the "K-quant" method, medium variant. Mixed precision: most tensors go to 4-bit, sensitive ones (embeddings, output layer) fall back to higher precision (the 181 fallback warning is normal and expected).
- **6.05 BPW** — 6.05 bits per weight on average (slightly above 4 due to the fallbacks).
- Completed in 413ms on macOS.

### What about the mmproj (vision encoder)?

`mmproj-SmolVLM-256M-Instruct-Q8_0.gguf` (~99MB) was **not quantized** — it stays at Q8_0. To quantize it, the F16 mmproj would need to be downloaded separately from HuggingFace and the same `llama-quantize` command run on it. It was not done here because the vision encoder runs only once per image (not in the token generation loop), and Q8_0 performance was acceptable after the `-O3` optimization fix.

---

## Image Description Algorithm

This section describes the full algorithm the model follows when generating a description for a given image.

### Step 1 — Image enters as raw bytes

The user selects a gallery image or takes a photo. In Kotlin, the image is read from the content URI and resized to max 512×512 before anything else:

```kotlin
val imageBytes = loadAndResizeImage(context, imageUri, maxSize = 512)
```

The bytes are a standard JPEG at this point — nothing model-specific yet.

### Step 2 — JPEG → raw RGB pixels

Inside `llm_inference.cpp`, the JPEG bytes are passed to **stb_image** (a C single-header image decoder bundled in llama.cpp):

```cpp
mtmd_helper_bitmap_init_from_buf(ic->mtmd, imageData, imgLen, false)
```

This decodes the JPEG into a raw `H × W × 3` array of RGB uint8 values in memory.

### Step 3 — Vision encoder — SigLIP ViT

The raw pixels go through the **vision encoder** inside the mmproj GGUF. This is a **Vision Transformer (ViT)** using the SigLIP architecture:

1. **Resize** the bitmap to exactly **384×384** pixels
2. **Patch split** — divide into 27×27 = **729 non-overlapping patches** of 14×14 pixels each
3. **Linear projection** — each patch is flattened and projected to a 1152-dimensional embedding vector
4. **Positional encoding** — 2D sinusoidal position embeddings added (tells the model where each patch is)
5. **Transformer layers** — the 729 patch embeddings pass through all ViT transformer layers (self-attention + FFN), letting patches attend to each other
6. Output: **729 contextual patch embeddings** of dim 1152

This is the pure vision understanding step — the model learns what objects, textures, and spatial relationships exist in the image.

### Step 4 — Projection MLP — vision → language space

The 729 SigLIP embeddings live in "vision space" (dim 1152). The language model works in "language space" (dim 576 for SmolVLM-500M). A small **2-layer MLP** (the "projector", also in the mmproj GGUF) maps them across:

```
729 × 1152  →  [pixel shuffle + MLP]  →  64 × 576
```

SmolVLM uses a **pixel shuffle / spatial merge** operation to compress 729 patches down to **64 image tokens**. This is the key reason SmolVLM is fast on mobile — most VLMs pass all 729 tokens to the LLM; SmolVLM compresses to 64.

Output: **64 image token embeddings** in the LLM's embedding space (dim 576).

### Step 5 — Prompt tokenization + chunk assembly

The text prompt is tokenized in parallel:

```
"<|im_start|>User:<__media__>\nDescribe this image.<|im_end|>\n<|im_start|>Assistant:\n"
```

`mtmd_tokenize` splits this at the `<__media__>` marker and produces a list of **chunks**:

```
[ TextChunk:  "<|im_start|>User:"                                        ]
[ ImageChunk: 64 embeddings                                               ]
[ TextChunk:  "\nDescribe this image.<|im_end|>\n<|im_start|>Assistant:\n"]
```

### Step 6 — LLM prefill — processing the full context

`mtmd_helper_eval_chunks` feeds all chunks through the **language model** (SmolLM2-500M, 30 transformer layers):

- Text tokens are looked up in the embedding table as normal
- Image embeddings from step 4 are inserted directly **in place of** the `<__media__>` position
- All tokens (text + image) are processed together in one forward pass (batched)
- The LLM's self-attention layers let every text token attend to every image token and vice versa

After this prefill, the **KV cache** holds the full compressed representation of both the image and the prompt. `n_past` = 64 (image) + ~18 (text) = ~82 tokens.

### Step 7 — Autoregressive token generation

The model now generates the description **one token at a time**:

```
loop:
  1. Run one LLM forward pass from KV cache position n_past
  2. Get logits over the vocabulary (~49,152 tokens)
  3. Sample next token via: top-k(40) → top-p(0.9) → temperature(0.7)
  4. Convert token ID → UTF-8 string fragment
  5. Send fragment to Kotlin via JNI callback → appears in UI
  6. Append token to KV cache, increment n_past
  7. Repeat until EOS token or maxTokens (512) reached
```

Each iteration is one full forward pass through all 30 LLM layers — this is the main cost per token.

### Step 8 — Why it "understands" the image

During prefill (step 6), **every text token can attend to every image token** via self-attention. So when generating "a cat sitting on a red sofa", the word "red" is influenced by the image patch embeddings that encoded that colour region. The 64 compressed image tokens carry the spatial and semantic information from all 729 original patches, and the LLM has seen millions of (image, text) pairs during training that teach it to interpret those embeddings as visual content.

### Summary diagram

```
JPEG bytes (512×512 max)
  └── stb_image decode → raw RGB (H×W×3)
        └── SigLIP ViT:
              resize → 384×384
              patch split → 729 patches (14×14px each)
              linear project + pos encoding
              transformer layers (self-attention between patches)
              → 729 patch embeddings (dim 1152)
                    └── Projection MLP + pixel shuffle:
                          729×1152 → 64×576
                          → 64 image token embeddings
                                └── LLM prefill (SmolLM2-500M, 30 layers):
                                      [text tokens] + [64 image tokens] → KV cache
                                            └── Autoregressive decode:
                                                  sample → token → UI → repeat
                                                  until EOS
```

---

## Key Technology Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material3 |
| State management | `StateFlow` + `collectAsStateWithLifecycle` |
| Async / streaming | Kotlin Coroutines + `callbackFlow` |
| Dependency injection | Hilt (Dagger) |
| Image loading (UI) | Coil |
| Native inference | llama.cpp (GGML 0.15.1) |
| Multimodal API | mtmd (llama.cpp `tools/mtmd/`) |
| ARM optimisation | KleidiAI + OpenMP (NDK libomp) |
| JNI bridge | C++17, NDK 27 / Clang 18 |
| Build | Gradle 8.9, AGP 8.7.3, CMake 3.22.1 |
