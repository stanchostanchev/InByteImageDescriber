# InByte Image Describer — Execution Flow

---

## 1. App Startup

```
Android OS
  └── launches InByteApplication
        └── @HiltAndroidApp → Hilt builds DI component graph
              └── AppModule.provideLlamaEngine() → LlamaEngine singleton created
                    └── System.loadLibrary("inbyte-inference") → libinbyte-inference.so loaded
```

**`InByteApplication.kt`**
```kotlin
@HiltAndroidApp
class InByteApplication : Application()
```

**`di/AppModule.kt`**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideLlamaEngine(): LlamaEngine = LlamaEngine()
}
```

**`inference/LlamaEngine.kt`** — library loaded when singleton is first constructed
```kotlin
companion object {
    init {
        System.loadLibrary("inbyte-inference")
    }
}
```

---

## 2. Screen Init

```
MainActivity.onCreate()
  └── setContent { InByteTheme { ChatScreen() } }
        └── hiltViewModel() → ChatViewModel injected with LlamaEngine
              └── ChatViewModel.init { loadModelFromAssets() }
```

**`MainActivity.kt`**
```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InByteTheme {
                ChatScreen()
            }
        }
    }
}
```

**`ui/chat/ChatViewModel.kt`**
```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val llamaEngine: LlamaEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadModelFromAssets()
    }
```

---

## 3. Model Loading (background, IO thread)

```
ChatViewModel.loadModelFromAssets()   [Dispatchers.IO]
  │
  ├── uiState.loadingMessage = "Preparing model files…"
  │     → ChatScreen shows loading overlay
  │
  ├── ModelSetupHelper.prepareModels()
  │     ├── if Q4_K_M.gguf not in filesDir → copy from assets (~119 MB)
  │     └── if mmproj.gguf not in filesDir → copy from assets (~99 MB)
  │
  ├── uiState.loadingMessage = "Loading model into memory…"
  │
  └── LlamaEngine.loadModel(modelPath, clipPath, contextSize=2048, threads=4)
        └── [JNI] nativeLoadModel()
              ├── llama_backend_init()
              ├── llama_load_model_from_file()     ← loads Q4_K_M weights
              ├── llama_new_context_with_model()   ← allocates KV cache, flash attention
              ├── mtmd_init_from_file()            ← loads vision encoder (mmproj)
              └── llama_sampler_chain_init()       ← top-k → top-p → temp → dist

  uiState.isModelLoaded = true  →  loading overlay dismissed, chat input enabled
```

**`ui/chat/ChatViewModel.kt`**
```kotlin
private fun loadModelFromAssets() {
    viewModelScope.launch(Dispatchers.IO) {
        _uiState.update { it.copy(loadingMessage = "Preparing model files…") }
        try {
            val paths = ModelSetupHelper.prepareModels(appContext) { msg ->
                _uiState.update { it.copy(loadingMessage = msg) }
            }
            _uiState.update { it.copy(loadingMessage = "Loading model into memory…") }
            val loaded = llamaEngine.loadModel(
                modelPath     = paths.modelPath,
                clipModelPath = paths.clipPath,
                contextSize   = 2048,
                threads       = 4,
            )
            _uiState.update {
                it.copy(
                    isModelLoaded  = loaded,
                    loadingMessage = null,
                    errorMessage   = if (!loaded) "Failed to load model." else null,
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(loadingMessage = null, errorMessage = "Setup error: ${e.message}") }
        }
    }
}
```

**`inference/ModelSetupHelper.kt`**
```kotlin
suspend fun prepareModels(context: Context, onProgress: (String) -> Unit): ModelPaths =
    withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
        val modelFile = File(modelsDir, "SmolVLM-256M-Instruct-Q4_K_M.gguf")
        val clipFile  = File(modelsDir, "mmproj-SmolVLM-256M-Instruct-Q8_0.gguf")

        if (!modelFile.exists()) {
            onProgress("Copying model to internal storage…")
            copyAsset(context, MODEL_ASSET, modelFile)
        }
        if (!clipFile.exists()) {
            onProgress("Copying vision projector…")
            copyAsset(context, MMPROJ_ASSET, clipFile)
        }
        ModelPaths(modelFile.absolutePath, clipFile.absolutePath)
    }

private fun copyAsset(context: Context, assetPath: String, dest: File) {
    context.assets.open(assetPath).use { input ->
        dest.outputStream().use { output -> input.copyTo(output, bufferSize = 8 * 1024 * 1024) }
    }
}
```

**`cpp/llm_inference.cpp`** — `nativeLoadModel`
```cpp
llama_backend_init();

llama_model_params mparams = llama_model_default_params();
mparams.n_gpu_layers = 0;
ic->model = llama_load_model_from_file(modelPath, mparams);
ic->vocab  = llama_model_get_vocab(ic->model);

llama_context_params cparams = llama_context_default_params();
cparams.n_ctx           = static_cast<uint32_t>(nCtx);
cparams.n_threads       = static_cast<uint32_t>(nThreads);
cparams.n_threads_batch = static_cast<uint32_t>(nThreads);
cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
ic->ctx = llama_new_context_with_model(ic->model, cparams);

mtmd_context_params mparams_m = mtmd_context_params_default();
mparams_m.use_gpu       = false;
mparams_m.n_threads     = static_cast<int>(nThreads);
mparams_m.print_timings = false;
mparams_m.warmup        = false;
ic->mtmd = mtmd_init_from_file(clipPath, ic->model, mparams_m);

llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
ic->sampler = llama_sampler_chain_init(sparams);
llama_sampler_chain_add(ic->sampler, llama_sampler_init_top_k(40));
llama_sampler_chain_add(ic->sampler, llama_sampler_init_top_p(0.9f, 1));
llama_sampler_chain_add(ic->sampler, llama_sampler_init_temp(0.7f));
llama_sampler_chain_add(ic->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
```

---

## 4. User Selects / Captures Image

```
[Gallery path]
User taps AddPhotoAlternate button
  └── PickVisualMedia launcher → system photo picker
        └── uri returned → ChatViewModel.onImageSelected(uri)
              └── uiState.pendingImageUri = uri
                    → PendingImagePreview thumbnail appears above input bar

[Camera path]
User taps CameraAlt button
  └── RequestPermission launcher
        ├── [first time] system permission dialog → user taps Allow
        └── permission granted
              └── createCameraUri() → FileProvider URI in cache/camera/photo_*.jpg
                    └── TakePicture launcher → system camera app
                          └── photo saved → ChatViewModel.onImageSelected(uri)
                                └── uiState.pendingImageUri = uri
```

**`ui/chat/ChatScreen.kt`** — gallery launcher
```kotlin
val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    uri?.let(onImageSelect)
}

IconButton(
    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    enabled = isModelLoaded && !isGenerating,
) {
    Icon(Icons.Default.AddPhotoAlternate, "Pick image", ...)
}
```

**`ui/chat/ChatScreen.kt`** — camera launcher
```kotlin
var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
    if (success) cameraUri?.let(onImageSelect)
}
val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
        val uri = createCameraUri(context)
        cameraUri = uri
        camera.launch(uri)
    }
}

IconButton(
    onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
    enabled = isModelLoaded && !isGenerating,
) {
    Icon(Icons.Default.CameraAlt, "Take photo", ...)
}
```

**`ui/chat/ChatScreen.kt`** — FileProvider URI creation
```kotlin
private fun createCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File.createTempFile("photo_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
```

**`ui/chat/ChatViewModel.kt`** — image selected handler
```kotlin
fun onImageSelected(uri: Uri) {
    _uiState.update { it.copy(pendingImageUri = uri) }
}
```

---

## 5. User Sends Message

```
User types prompt (optional) → taps Send
  └── ChatViewModel.sendMessage()
        ├── append ChatMessage(role=USER, text=prompt, imageUri=pendingUri)
        ├── append ChatMessage(role=ASSISTANT, text="", isStreaming=true)
        ├── uiState.isGenerating = true
        └── uiState.pendingImageUri = null  → thumbnail cleared
```

**`ui/chat/ChatViewModel.kt`**
```kotlin
fun sendMessage() {
    val state = _uiState.value
    if (state.isGenerating || !state.isModelLoaded) return

    val userText = state.inputText.trim().ifEmpty { "Describe this image." }
    val imageUri = state.pendingImageUri

    val userMsg = ChatMessage(
        role     = MessageRole.USER,
        text     = if (imageUri != null && state.inputText.isBlank()) "" else userText,
        imageUri = imageUri,
    )
    val assistantId = UUID.randomUUID().toString()
    val assistantMsg = ChatMessage(
        id          = assistantId,
        role        = MessageRole.ASSISTANT,
        text        = "",
        isStreaming = true,
    )

    _uiState.update {
        it.copy(
            messages        = it.messages + userMsg + assistantMsg,
            inputText       = "",
            pendingImageUri = null,
            isGenerating    = true,
            errorMessage    = null,
        )
    }
```

---

## 6. Image Preprocessing (Kotlin, before JNI)

```
LlamaEngine.describeImage()   [Dispatchers.Default via callbackFlow + flowOn]
  └── loadAndResizeImage(uri, maxSize=512)
        ├── read raw bytes from ContentResolver
        ├── BitmapFactory.decodeByteArray() [bounds only, inJustDecodeBounds=true]
        ├── if image > 512×512:
        │     scale = 512 / max(width, height)
        │     Bitmap.createScaledBitmap() → scaled bitmap
        │     compress to JPEG 90% → byte array
        └── return resized JPEG bytes  (~50–200 KB vs original ~5 MB)
```

> Resizing prevents SmolVLM's "anyres" tiling from creating up to 9 tiles.
> With max 512×512 input, the vision encoder always processes exactly 1 tile (384×384) — ~9× faster.

**`inference/LlamaEngine.kt`**
```kotlin
fun describeImage(
    context: Context,
    imageUri: Uri,
    prompt: String,
    maxTokens: Int = 512,
): Flow<String> = callbackFlow {
    val imageBytes = loadAndResizeImage(context, imageUri, maxSize = 512)
        ?: run { close(IllegalStateException("Cannot read image")); return@callbackFlow }

    nativeDescribeImage(
        handle        = nativeHandle,
        imageBytes    = imageBytes,
        prompt        = prompt,
        maxTokens     = maxTokens,
        tokenCallback = { token -> trySend(token) },
    )
    close()
}.flowOn(Dispatchers.Default)

private fun loadAndResizeImage(context: Context, uri: Uri, maxSize: Int): ByteArray? {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    val w = opts.outWidth; val h = opts.outHeight
    if (w <= maxSize && h <= maxSize) return raw
    val scale = maxSize.toFloat() / maxOf(w, h)
    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
    val scaled = Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
    bmp.recycle()
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
    scaled.recycle()
    return out.toByteArray()
}
```

---

## 7. Native Inference (C++, JNI)

```
nativeDescribeImage(handle, imageBytes, prompt, maxTokens=512, tokenCallback)
  │
  ├── llama_memory_clear()          ← reset KV cache for new conversation turn
  │
  ├── build prompt string:
  │   "<|im_start|>User:<__media__>\n{prompt}<|im_end|>\n<|im_start|>Assistant:\n"
  │
  ├── mtmd_helper_bitmap_init_from_buf()
  │     └── stb_image decodes JPEG bytes → raw RGB bitmap in memory
  │
  ├── mtmd_tokenize()
  │     ├── tokenises text portions of the prompt → token IDs
  │     └── registers image as an image chunk (placeholder for embeddings)
  │
  ├── mtmd_helper_eval_chunks()
  │     ├── [image chunk] vision encoder forward pass:
  │     │     resize bitmap → 384×384
  │     │     SigLIP ViT → 64 image embedding vectors
  │     │     projection MLP → mapped to LLM embedding space
  │     └── [all chunks] llama_decode() prefill:
  │           feeds image embeddings + text tokens through all 30 LM layers
  │           n_past = total tokens processed
  │
  └── generation loop (up to maxTokens):
        ├── llama_sampler_sample()     ← top-k(40) → top-p(0.9) → temp(0.7)
        ├── if token == EOS → break
        ├── llama_token_to_piece()     ← token ID → UTF-8 string fragment
        ├── invokeCallback(token)      ← calls Kotlin lambda with the fragment
        └── llama_decode(token)        ← feed token back, advance n_past
```

**`cpp/llm_inference.cpp`**
```cpp
// Reset KV cache
llama_memory_clear(llama_get_memory(ic->ctx), true);

// Build prompt
std::string fullPrompt = hasImage
    ? "<|im_start|>User:" + ic->marker + "\n" + userPrompt + "<|im_end|>\n<|im_start|>Assistant:\n"
    : "<|im_start|>User:\n" + userPrompt + "<|im_end|>\n<|im_start|>Assistant:\n";

// Decode image bytes → bitmap
auto bmpWrapper = mtmd_helper_bitmap_init_from_buf(
    ic->mtmd,
    reinterpret_cast<const unsigned char*>(imgData),
    static_cast<size_t>(imgLen),
    /*placeholder=*/false);

// Tokenise prompt + image
mtmd_input_text inputText{ fullPrompt.c_str(), /*add_special=*/true, /*parse_special=*/true };
const mtmd_bitmap* bitmaps[1] = { bmpWrapper.bitmap };
mtmd_input_chunks* chunks = mtmd_input_chunks_init();
mtmd_tokenize(ic->mtmd, chunks, &inputText, bitmaps, 1);

// Vision encode + LLM prefill
mtmd_helper_eval_chunks(
    ic->mtmd, ic->ctx, chunks,
    /*n_past=*/0, /*seq_id=*/0, /*n_batch=*/512,
    /*logits_last=*/true, &n_past);

// Generation loop
const llama_token eosId = llama_vocab_eos(ic->vocab);
for (int i = 0; i < maxTokens; i++) {
    llama_token tok = llama_sampler_sample(ic->sampler, ic->ctx, -1);
    if (tok == eosId || tok < 0) break;

    char piece[256] = {};
    int len = llama_token_to_piece(ic->vocab, tok, piece, sizeof(piece), 0, false);
    if (len > 0) invokeCallback(env, tokenCb, std::string(piece, len));

    llama_batch batch = llama_batch_get_one(&tok, 1);
    if (llama_decode(ic->ctx, batch) != 0) break;
    n_past++;
}
```

---

## 8. Token Streaming Back to UI

```
[C++ invokeCallback]
  └── JNIEnv.CallObjectMethod → Kotlin lambda (tokenCallback)
        └── callbackFlow.trySend(token)
              └── Flow<String> emits token   [Dispatchers.Default]
                    └── ChatViewModel.collect { token →
                          StateFlow.update { assistant.text += token }
                        }
                          └── ChatScreen recomposes
                                └── ChatBubble re-renders with new text
                                      └── user sees text grow word by word
```

**`cpp/llm_inference.cpp`** — callback invocation
```cpp
void invokeCallback(JNIEnv* env, jobject cb, const std::string& token) {
    jclass    cls = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (mid) {
        jstring jstr = env->NewStringUTF(token.c_str());
        env->CallObjectMethod(cb, mid, jstr);
        env->DeleteLocalRef(jstr);
    }
    env->DeleteLocalRef(cls);
}
```

**`inference/LlamaEngine.kt`** — Flow emission
```kotlin
nativeDescribeImage(
    handle        = nativeHandle,
    imageBytes    = imageBytes,
    prompt        = prompt,
    maxTokens     = maxTokens,
    tokenCallback = { token -> trySend(token) },
)
```

**`ui/chat/ChatViewModel.kt`** — state update per token
```kotlin
flow.collect { token ->
    _uiState.update { s ->
        s.copy(messages = s.messages.map { msg ->
            if (msg.id == assistantId) msg.copy(text = msg.text + token) else msg
        })
    }
}
```

**`ui/chat/ChatScreen.kt`** — streaming cursor shown while `isStreaming = true`
```kotlin
if (message.isStreaming) {
    Spacer(Modifier.width(4.dp))
    StreamingCursor()
}

@Composable
private fun StreamingCursor() {
    val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .size(2.dp, 14.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
    )
}
```

---

## 9. Generation Complete

```
nativeDescribeImage() returns
  └── callbackFlow.close()
        └── flow collection ends
              └── ChatViewModel finally block:
                    ├── assistant message: isStreaming = false  → cursor disappears
                    └── uiState.isGenerating = false  → Send button re-enabled
```

**`ui/chat/ChatViewModel.kt`**
```kotlin
} finally {
    _uiState.update { s ->
        s.copy(
            messages     = s.messages.map { if (it.id == assistantId) it.copy(isStreaming = false) else it },
            isGenerating = false,
        )
    }
}
```

---

## 10. ViewModel Destroyed (user leaves app)

```
ChatViewModel.onCleared()
  └── LlamaEngine.free()
        └── nativeFree(handle)
              ├── llama_sampler_free()
              ├── mtmd_free()
              ├── llama_free()         ← releases KV cache
              ├── llama_free_model()   ← releases weight memory
              └── llama_backend_free()
```

**`ui/chat/ChatViewModel.kt`**
```kotlin
override fun onCleared() {
    super.onCleared()
    llamaEngine.free()
}
```

**`inference/LlamaEngine.kt`**
```kotlin
fun free() {
    if (nativeHandle != 0L) {
        nativeFree(nativeHandle)
        nativeHandle = 0L
    }
}
```

**`cpp/llm_inference.cpp`**
```cpp
auto* ic = reinterpret_cast<InferenceContext*>(handle);
if (!ic) return;
if (ic->sampler) llama_sampler_free(ic->sampler);
if (ic->mtmd)    mtmd_free(ic->mtmd);
if (ic->ctx)     llama_free(ic->ctx);
if (ic->model)   llama_free_model(ic->model);
delete ic;
llama_backend_free();
```

---

## Thread Summary

| Thread | Work |
|---|---|
| **Main (UI)** | Compose recomposition, user input events |
| **Dispatchers.IO** | Model file copy (`ModelSetupHelper`), model loading (`nativeLoadModel`) |
| **Dispatchers.Default** | Image resize, JNI inference call (`nativeDescribeImage`), `Flow` emission |
| **OpenMP thread pool (NDK)** | Parallel tensor ops inside ggml — 4 threads (performance cores only) |
