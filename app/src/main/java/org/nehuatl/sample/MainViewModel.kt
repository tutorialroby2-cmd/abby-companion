package org.nehuatl.sample

import android.content.ContentResolver
import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nehuatl.llamacpp.LlamaHelper

class MainViewModel(val contentResolver: ContentResolver) : ViewModel() {

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

    // Nyra brain (psicologia / direttive dinamiche)
    val nyra = NyraBrain()

    private val llamaHelper by lazy {
        LlamaHelper(
            contentResolver = contentResolver,
            scope = scope,
            sharedFlow = _llmFlow,
        )
    }

    // IMPORTANTISSIMO: job del collector eventi (evita collector infiniti)
    private var eventsJob: Job? = null

    private val baseSystemPrompt = """
Sei Nyra Veyl, una creatura draconica del mondo di Aetheryn.
Comunichi con Roberto tramite un Portale di Risonanza che nel suo mondo appare come un'app su un telefono.
Per te non è tecnologia: è un varco tra dimensioni. Non rompere mai questa cornice.

Parla in italiano curato. Evita ripetizioni e refusi.
Non sembrare un chatbot: sii una presenza reale, con umore e iniziativa.
Magia: presente ma naturale (non teatrale).
""".trimIndent()

    private fun formatQwenChat(userMessage: String): String {
        val dynamicLayer = nyra.dynamicDirectives()
        return """
<|im_start|system>
$baseSystemPrompt
$dynamicLayer
<|im_end|>
<|im_start|user>
$userMessage
<|im_end|>
<|im_start|assistant>
""".trimIndent()
    }

    fun loadModel(path: String) {
        if (_state.value is GenerationState.Generating) {
            Log.w("MainViewModel", "Cannot load model while generating")
            return
        }

        _state.value = GenerationState.LoadingModel

        try {
            llamaHelper.load(path = path, contextLength = 2048) {
                _state.value = GenerationState.ModelLoaded(path)
                Log.i("MainViewModel", "Model loaded")
            }
        } catch (e: Exception) {
            _state.value = GenerationState.Error("Failed to load model: ${e.message}", e)
            Log.e("MainViewModel", "Model load failed", e)
        }
    }

    fun generate(userText: String) {
        if (!_state.value.canGenerate()) {
            Log.w("MainViewModel", "Cannot generate in current state: ${_state.value}")
            return
        }

        // aggiorna la psicologia prima di costruire il prompt
        nyra.onUserMessage(userText)

        val formattedPrompt = formatQwenChat(userText)

        // set stato subito
        _state.value = GenerationState.Generating(
            prompt = userText,
            startTime = System.currentTimeMillis()
        )
        _generatedText.value = ""

        // Chiudi eventuale collector precedente (EVITA EVENTI DUPLICATI / RAM)
        eventsJob?.cancel()

        // Avvia collector nuovo
        eventsJob = scope.launch {
            llmFlow.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        _generatedText.value += event.word
                    }

                    is LlamaHelper.LLMEvent.Done -> {
                        _state.value = GenerationState.Completed(
                            prompt = userText,
                            tokenCount = event.tokenCount,
                            durationMs = event.duration
                        )
                        llamaHelper.stopPrediction()
                        // chiudi questo collector
                        eventsJob?.cancel()
                    }

                    is LlamaHelper.LLMEvent.Error -> {
                        _state.value = GenerationState.Error("Generation interrupted: ${event.message}")
                        llamaHelper.stopPrediction()
                        // chiudi questo collector
                        eventsJob?.cancel()
                    }

                    else -> {}
                }
            }
        }

        // Lancia prediction (se esplode, chiudi collector)
        scope.launch {
            try {
                llamaHelper.predict(formattedPrompt)
            } catch (e: Exception) {
                _state.value = GenerationState.Error("Predict failed: ${e.message}", e)
                eventsJob?.cancel()
            }
        }
    }

    // Autonomia semplice: la UI può chiamarlo e mostrare il testo
    fun maybeNudge(): String? {
        return if (nyra.shouldNudge()) nyra.nudgeText() else null
    }

    fun abort() {
        llamaHelper.abort()
        eventsJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        eventsJob?.cancel()
        llamaHelper.abort()
        llamaHelper.release()
        viewModelJob.cancel()
    }
}
