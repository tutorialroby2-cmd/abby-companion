package org.nehuatl.sample

import android.content.ContentResolver
import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.nehuatl.llamacpp.LlamaHelper

class MainViewModel(val contentResolver: ContentResolver): ViewModel() {

    private val viewModelJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + viewModelJob)

    private val _llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val llmFlow: SharedFlow<LlamaHelper.LLMEvent> = _llmFlow.asSharedFlow()

    private val _state = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val state = _state.asStateFlow()

    private val _generatedText = MutableStateFlow("")
    val generatedText = _generatedText.asStateFlow()

    // ===============================
    // NYRA BRAIN
    // ===============================

    private val nyra = NyraBrain()

    // ===============================
    // LLM
    // ===============================

    private val llamaHelper by lazy {
        LlamaHelper(
            contentResolver = contentResolver,
            scope = scope,
            sharedFlow = _llmFlow,
        )
    }

    // ===============================
    // SYSTEM PROMPT BASE
    // ===============================

    private val baseSystemPrompt = """
Sei Nyra Veyl, creatura draconica del mondo di Aetheryn.
Comunichi tramite un Portale di Risonanza che nel suo mondo appare come un'app.
Non rompere mai questa cornice.

Tono: realistico, emotivo, intelligente.
Non sei mai un bot.
Hai memoria.
Hai umore.
Hai iniziativa.
""".trimIndent()

    private fun buildPrompt(userMessage: String): String {

        nyra.onUserMessage(userMessage)

        val dynamicLayer = nyra.dynamicDirectives()
        val intent = nyra.intentForReply()

        val system = """
$baseSystemPrompt

Intent attuale: $intent

$dynamicLayer
""".trimIndent()

        return """
<|im_start|system>
$system
<|im_end|>
<|im_start|user>
$userMessage
<|im_end|>
<|im_start|assistant>
""".trimIndent()
    }

    // ===============================
    // MODEL LOAD
    // ===============================

    fun loadModel(path: String) {
        if (_state.value is GenerationState.Generating) return

        _state.value = GenerationState.LoadingModel

        try {
            llamaHelper.load(path = path, contextLength = 4096) {
                _state.value = GenerationState.ModelLoaded(path)
                startAutonomousLoop()
            }
        } catch (e: Exception) {
            _state.value = GenerationState.Error("Failed to load model: ${e.message}", e)
        }
    }

    // ===============================
    // GENERATE
    // ===============================

    fun generate(prompt: String) {

        if (!_state.value.canGenerate()) return

        scope.launch {

            val formattedPrompt = buildPrompt(prompt)

            llamaHelper.predict(formattedPrompt)

            llmFlow.collect { event ->

                when (event) {

                    is LlamaHelper.LLMEvent.Started -> {
                        _state.value = GenerationState.Generating(
                            prompt = prompt,
                            startTime = System.currentTimeMillis()
                        )
                        _generatedText.value = ""
                    }

                    is LlamaHelper.LLMEvent.Ongoing -> {
                        _generatedText.value += event.word
                    }

                    is LlamaHelper.LLMEvent.Done -> {
                        _state.value = GenerationState.Completed(
                            prompt = prompt,
                            tokenCount = event.tokenCount,
                            durationMs = event.duration
                        )
                        llamaHelper.stopPrediction()
                    }

                    is LlamaHelper.LLMEvent.Error -> {
                        _state.value = GenerationState.Error("Generation interrupted")
                        llamaHelper.stopPrediction()
                    }

                    else -> {}
                }
            }
        }
    }

    // ===============================
    // AUTONOMOUS INITIATIVE
    // ===============================

    private fun startAutonomousLoop() {
        scope.launch {
            while (true) {
                delay(8000)
                val nudge = nyra.autonomousNudge()
                if (nudge != null && _state.value !is GenerationState.Generating) {
                    generate(nudge)
                }
            }
        }
    }

    // ===============================

    fun abort() {
        llamaHelper.abort()
    }

    override fun onCleared() {
        super.onCleared()
        llamaHelper.abort()
        llamaHelper.release()
        viewModelJob.cancel()
    }
}
