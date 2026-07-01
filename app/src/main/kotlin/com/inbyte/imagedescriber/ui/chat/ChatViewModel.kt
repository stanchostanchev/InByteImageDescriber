package com.inbyte.imagedescriber.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inbyte.imagedescriber.inference.InstructionRepository
import com.inbyte.imagedescriber.inference.LlamaEngine
import com.inbyte.imagedescriber.inference.ModelSetupHelper
import com.inbyte.imagedescriber.inference.TranslationHelper
import com.inbyte.imagedescriber.model.ChatMessage
import com.inbyte.imagedescriber.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

// Set to true to show Qwen3 <think>…</think> reasoning in the chat
private const val SHOW_QWEN_REASONING = false

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val llamaEngine: LlamaEngine,
    private val translationHelper: TranslationHelper,
    private val instructionRepository: InstructionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var smolvlmPath: String = ""
    private var clipPath: String = ""
    private var qwenPath: String = ""
    @Volatile private var isSending = false

    init {
        loadModelFromAssets()
    }

    private fun loadModelFromAssets() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(loadingMessage = "Preparing model files…") }
            try {
                instructionRepository.load(appContext)
                val paths = ModelSetupHelper.prepareModels(appContext) { msg ->
                    _uiState.update { it.copy(loadingMessage = msg) }
                }
                smolvlmPath = paths.modelPath
                clipPath    = paths.clipPath
                qwenPath    = paths.qwenPath

                _uiState.update { it.copy(loadingMessage = "Loading vision model…") }
                val loaded = llamaEngine.loadModel(
                    modelPath     = smolvlmPath,
                    clipModelPath = clipPath,
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

    /** Ensures SmolVLM is loaded. Returns true on success. LlamaEngine itself
     *  dedupes if it's already the active model (tracked on the shared singleton,
     *  so it stays correct even when DescriptionViewModel swaps models in between). */
    private suspend fun ensureSmolVLM(): Boolean {
        if (llamaEngine.loadedModelPath == smolvlmPath) return true
        _uiState.update { it.copy(loadingMessage = "Loading vision model…") }
        val ok = withContext(Dispatchers.IO) {
            llamaEngine.loadModel(smolvlmPath, clipPath, 2048, 4)
        }
        _uiState.update { it.copy(loadingMessage = null) }
        return ok
    }

    /** Ensures Qwen3 is loaded. Returns true on success. */
    private suspend fun ensureQwen3(): Boolean {
        if (llamaEngine.loadedModelPath == qwenPath) return true
        _uiState.update { it.copy(loadingMessage = "Loading text model…") }
        val ok = withContext(Dispatchers.IO) {
            // Empty clip path = text-only mode; same llm_inference.so, no symbol conflicts
            llamaEngine.loadModel(qwenPath, "", 3072, 4)
        }
        _uiState.update { it.copy(loadingMessage = null) }
        return ok
    }

    fun onImageSelected(uri: Uri) {
        _uiState.update { it.copy(pendingImageUri = uri) }
    }

    fun clearPendingImage() {
        _uiState.update { it.copy(pendingImageUri = null) }
    }

    fun stopGeneration() {
        llamaEngine.cancel()
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendStory(text: String) {
        _uiState.update { it.copy(messages = emptyList(), inputText = text) }
        sendMessage()
    }

    fun sendMessage() {
        if (isSending) return
        isSending = true
        val state = _uiState.value
        if (state.isGenerating || !state.isModelLoaded) { isSending = false; return }

        val userText = state.inputText.trim().ifEmpty { "Describe the image" }
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
                inputText       = "Describe the image",
                pendingImageUri = null,
                isGenerating    = true,
                errorMessage    = null,
            )
        }
        isSending = false

        viewModelScope.launch {
            try {
                if (imageUri != null) {
                    // ── Vision path: SmolVLM describes the image ──────────────
                    if (!ensureSmolVLM()) {
                        finishMessage(assistantId, "[Failed to load vision model]")
                        return@launch
                    }

                    val description = StringBuilder()
                    llamaEngine.describeImage(appContext, imageUri, userText)
                        .collect { token ->
                            description.append(token)
                            appendToken(assistantId, token)
                        }
                    appendToken(assistantId, "\n\n_SmolVLM 500M_")
                    markDone(assistantId)

                    // Subjects & Actions (code-based)
                    if (description.isNotBlank()) {
                        val concepts = extractConcepts(description.toString())
                        if (concepts.isNotBlank()) addMessage(concepts)
                    }

                } else {
                    // ── Text path: Qwen3 generates story / answers question ──
                    if (!ensureQwen3()) {
                        finishMessage(assistantId, "[Failed to load text model]")
                        return@launch
                    }

                    // Look up instruction from JSONL by keyword, fall back to generic prompt
                    val match = instructionRepository.findInstruction(userText)
                    val instruction = match?.second
                        ?: "Write a fairy tale about: $userText"
                    val category = match?.first
                    Log.d("InByteVM", "userText='$userText' category=$category instruction=${instruction.take(80)}")

                    _uiState.update { it.copy(matchedCategory = category) }

                    val chatmlPrompt =
                        "<|im_start|>system\nYou are a storyteller.\n<|im_end|>\n" +
                        "<|im_start|>user\n$instruction\n/no_think<|im_end|>\n" +
                        "<|im_start|>assistant\n"

                    suspend fun runGeneration(temperature: Float = 0.7f): String {
                        val output    = StringBuilder()
                        val thinkBuf  = StringBuilder()
                        var inThink = false
                        llamaEngine.generate(chatmlPrompt, maxTokens = 1500, temperature = temperature).collect { token ->
                            when {
                                token.contains("<think>")      -> inThink = true
                                token.contains("</think>")     -> inThink = false
                                token.contains("<|im_end|>")   -> { llamaEngine.cancel() }
                                token.contains("<|im_start|>") -> { llamaEngine.cancel() }
                                inThink -> thinkBuf.append(token)
                                else -> {
                                    output.append(token)
                                    appendToken(assistantId, token)
                                    if (isRepeating(output.toString()) || isMetaCommentary(output.toString())) {
                                        llamaEngine.cancel()
                                    }
                                }
                            }
                        }
                        // Trim at the last story-ending sentence and sync the displayed bubble
                        val trimmed = trimAtStoryEnd(output.toString())
                        if (trimmed != output.toString()) {
                            setMessageText(assistantId, trimmed)
                        }
                        return trimmed
                    }

                    val topicKeyword = extractTopicKeyword(userText)

                    fun clearBubble() {
                        _uiState.update { s ->
                            s.copy(messages = s.messages.map { m ->
                                if (m.id == assistantId) m.copy(text = "") else m
                            })
                        }
                    }

                    var qwenOutput = runGeneration()
                    if (qwenOutput.isBlank()) {
                        clearBubble()
                        qwenOutput = runGeneration()
                    }
                    // Topic-keyword check only when no instruction was matched (fallback path)
                    if (category == null && topicKeyword != null && !qwenOutput.lowercase().contains(topicKeyword)) {
                        clearBubble()
                        val retry = runGeneration(temperature = 0.5f)
                        if (retry.isNotBlank()) qwenOutput = retry
                    }
                    val correctedOutput = qwenOutput.trim()
                    val coherenceScore = if (correctedOutput.isNotBlank())
                        evaluateCoherence(correctedOutput) else null

                    val scoreLabel = if (coherenceScore != null) "\nCoherence: $coherenceScore / 5" else ""
                    appendToken(assistantId, "\n\n_Qwen3 1.7B_$scoreLabel")
                    markDone(assistantId)

                    Log.d("InByteVM", "correctedOutput blank=${correctedOutput.isBlank()} len=${correctedOutput.length}")
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Generation failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isGenerating = false, loadingMessage = null, matchedCategory = null) }
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun appendToken(msgId: String, token: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == msgId) m.copy(text = m.text + token) else m
            })
        }
    }

    private fun setMessageText(msgId: String, text: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == msgId) m.copy(text = text) else m
            })
        }
    }

    private fun markDone(msgId: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == msgId) m.copy(isStreaming = false) else m
            })
        }
    }

    private fun finishMessage(msgId: String, text: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == msgId) m.copy(text = text, isStreaming = false) else m
            })
        }
    }

    private fun addMessage(text: String) {
        _uiState.update { it.copy(messages = it.messages + ChatMessage(
            id          = UUID.randomUUID().toString(),
            role        = MessageRole.ASSISTANT,
            text        = text,
            isStreaming = false,
        )) }
    }

    private suspend fun addTranslation(sourceText: String) {
        val translId = UUID.randomUUID().toString()
        _uiState.update { it.copy(messages = it.messages + ChatMessage(
            id = translId, role = MessageRole.ASSISTANT, text = "🇧🇬 Превод…", isStreaming = true,
        )) }
        try {
            val ready = translationHelper.ensureModelDownloaded()
            Log.d("InByteVM", "translation model ready=$ready sourceLen=${sourceText.length}")
            val text  = if (ready) translationHelper.translate(sourceText.trim())
                        else "Преводът не е наличен офлайн."
            _uiState.update { s ->
                s.copy(messages = s.messages.map { m ->
                    if (m.id == translId) m.copy(text = "🇧🇬 $text", isStreaming = false) else m
                })
            }
        } catch (e: Exception) {
            _uiState.update { s ->
                s.copy(messages = s.messages.map { m ->
                    if (m.id == translId) m.copy(text = "🇧🇬 Грешка при превода.", isStreaming = false) else m
                })
            }
        }
    }

    // ── Rule-based internal coherence score ──────────────────────────────────

    private fun extractTopicKeyword(text: String): String? {
        val stopwords = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "about", "with", "from",
            "that", "this", "these", "those", "and", "or", "but", "in", "on",
            "at", "to", "of", "for", "by", "as", "it", "its", "my", "your",
            "his", "her", "our", "their", "me", "him", "us", "them", "story",
            "fairy", "tale", "make", "write", "create", "generate", "tell",
        )
        return text.lowercase()
            .split(Regex("[^a-z]+"))
            .firstOrNull { it.length > 2 && it !in stopwords }
    }

    private fun evaluateCoherence(story: String): Int {
        var score = 5

        // Deduct for pronoun inconsistency (character switches gender)
        val lower = story.lowercase()
        val heCount  = Regex("\\b(he|him|his)\\b").findAll(lower).count()
        val sheCount = Regex("\\b(she|her|hers)\\b").findAll(lower).count()
        if (heCount > 0 && sheCount > 0) score -= 2

        // Deduct for story cut off mid-sentence (no terminal punctuation at end)
        val trimmed = story.trimEnd()
        if (trimmed.isNotEmpty() && trimmed.last() !in listOf('.', '!', '?')) score -= 1

        // Deduct for very short output (likely incomplete)
        val wordCount = story.split(Regex("\\s+")).size
        if (wordCount < 15) score -= 2
        else if (wordCount < 25) score -= 1

        // Deduct for repeated phrases (3+ consecutive words appearing more than once)
        val words = story.lowercase().split(Regex("\\s+"))
        val trigrams = (0..words.size - 3).map { "${words[it]} ${words[it+1]} ${words[it+2]}" }
        if (trigrams.size != trigrams.distinct().size) score -= 1

        return score.coerceIn(1, 5)
    }

    // ── Pronoun coherence normalization ───────────────────────────────────────

    private fun normalizePronounGender(text: String): String {
        val lower = text.lowercase()

        val heCount  = Regex("\\b(he|him|his)\\b").findAll(lower).count()
        val sheCount = Regex("\\b(she|her|hers)\\b").findAll(lower).count()

        if (heCount == 0 || sheCount == 0) return text  // already consistent

        // Replace minority pronouns with the dominant set (case-preserving)
        val replaceHe  = sheCount > heCount
        return if (replaceHe) {
            text
                .replace(Regex("\\bHe\\b"), "She")
                .replace(Regex("\\bhe\\b"), "she")
                .replace(Regex("\\bHim\\b"), "Her")
                .replace(Regex("\\bhim\\b"), "her")
                .replace(Regex("\\bHis\\b"), "Her")
                .replace(Regex("\\bhis\\b"), "her")
        } else {
            text
                .replace(Regex("\\bShe\\b"), "He")
                .replace(Regex("\\bshe\\b"), "he")
                .replace(Regex("\\bHer\\b"), "Him")
                .replace(Regex("\\bher\\b"), "him")
                .replace(Regex("\\bHers\\b"), "His")
                .replace(Regex("\\bhers\\b"), "his")
        }
    }

    // ── Code-based Subjects/Actions extraction ────────────────────────────────

    private fun extractConcepts(text: String): String {
        val adjectives = setOf(
            "white","black","red","blue","green","yellow","orange","purple","pink",
            "brown","gray","grey","gold","golden","silver","beige","cream","ivory",
            "dark","light","bright","pale","deep","vivid","vibrant","rich","warm","cool",
            "large","small","big","little","tiny","huge","tall","short","long","wide",
            "narrow","thick","thin","round","flat","curved","straight","soft","hard",
            "smooth","rough","sharp","blunt","heavy","dense","sparse","full",
            "empty","open","closed","high","low","shallow","clear","cloudy",
            "fresh","dry","wet","old","new","young","ancient","modern","beautiful",
            "elegant","delicate","bold","subtle","simple","complex","natural","lush",
            "serene","peaceful","colorful","gentle","strong","single","multiple",
            "various","several","many","few","numerous","central","upper","lower","left",
            "right","front","back","inner","outer","top","bottom","side","middle"
        )
        val drawingWords = setOf(
            "painting","painted","paint","draw","drawn","drawing","sketch","canvas",
            "artwork","art","artist","brushstroke","brush","stroke","palette","pigment",
            "watercolor","oil","acrylic","pastel","charcoal","ink","illustration",
            "composition","depicted","depict","rendered","render","masterpiece",
            "portrait","landscape","impressionist","realism","realistic","stylized",
            "technique","medium","foreground","background","image","photo","picture",
            "photograph","scene","visual","frame","shown","seen","visible","captured",
            "featuring","framed","screenshot","webpage","page","website","web",
            "display","layout","grid","cell","designed","design","organized","format","navigation"
        )
        val stopWords = setOf(
            "a","an","the","and","or","but","in","on","at","to","for","of","with",
            "is","are","was","were","be","been","being","has","have","had","do","does",
            "did","will","would","could","should","may","might","shall","can","that",
            "this","these","those","it","its","they","their","there","here","as","by",
            "from","into","through","during","before","after","above","below","between",
            "each","both","few","more","most","other","some","such","no","not","only",
            "same","so","than","too","very","just","also","then","when","where","which",
            "who","what","how","all","any","i","he","she","we","you","his","her","our",
            "your","my","up","out","if","about","over","while","around","upon","one",
            "two","three","four","five","six","overall","appear","appears","seem","seems",
            "depicted","likely","used","search","specific","along","allows","easy",
            "way","helps","make","stand","focus","ease","brief"
        ) + drawingWords + adjectives

        val actionWords = setOf(
            "run","running","walk","walking","sit","sitting","stand","standing",
            "hold","holding","look","looking","show","showing","wear","wearing",
            "carry","carrying","place","placed","placing","move","moving","cover","covering",
            "fill","filling","grow","growing","bloom","blooming","hang","hanging",
            "float","floating","rise","rising","reflect","reflecting","extend","extending",
            "create","creating","form","forming","surround","surrounding",
            "illuminate","illuminating","decorate","decorating","lean","leaning",
            "reach","reaching","spread","spreading","fall","falling","flow","flowing",
            "lay","laying","rest","resting","face","facing"
        )

        val words = text.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }

        val freq = mutableMapOf<String, Int>()
        words.filter { it !in stopWords }.forEach { freq[it] = (freq[it] ?: 0) + 1 }

        fun isVerb(w: String) = w in actionWords ||
            (w.endsWith("ing") && w.length > 5 && w !in stopWords) ||
            (w.endsWith("ed")  && w.length > 4 && w !in stopWords)

        val ranked = freq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key.length })
            .map { it.key }

        val actions  = ranked.filter { isVerb(it) }.distinct().take(5)
        val subjects = ranked.filter { !isVerb(it) }.distinct().take(5)

        val sb = StringBuilder()
        if (subjects.isNotEmpty()) sb.append("Subjects:\n").append(subjects.joinToString("\n") { "• $it" })
        if (actions.isNotEmpty())  sb.append("\n\nActions:\n").append(actions.joinToString("\n") { "• $it" })
        return sb.toString().trim()
    }

    // Detect repetition by checking if a phrase of 40-120 chars repeats 3+ times at the end
    private fun isRepeating(text: String): Boolean {
        if (text.length < 120) return false
        val tail = text.takeLast(400)
        for (len in 40..120) {
            val phrase = tail.takeLast(len)
            val count = tail.windowed(len).count { it == phrase }
            if (count >= 3) return true
        }
        return false
    }

    private val metaCommentaryMarkers = listOf(
        "the text is", "the story is a", "this is an example", "the author's choice",
        "the author uses", "this story uses", "in this story,", "note:", "explanation:",
        "the passage", "as you can see", "this fairy tale",
    )

    private fun isMetaCommentary(text: String): Boolean {
        if (text.length < 200) return false
        val lower = text.lowercase()
        return metaCommentaryMarkers.any { lower.contains(it) }
    }

    private fun trimAtStoryEnd(text: String): String {
        val endMarkers = listOf("the end.", "the end!", "the end…", "the end")
        val lower = text.lowercase()
        for (marker in endMarkers) {
            val idx = lower.indexOf(marker)
            if (idx >= 0) {
                return text.substring(0, idx + marker.length)
            }
        }
        for (marker in metaCommentaryMarkers) {
            val idx = lower.indexOf(marker)
            if (idx >= 0) {
                return text.substring(0, idx).trimEnd()
            }
        }
        return text
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        llamaEngine.free()
        translationHelper.close()
    }
}
