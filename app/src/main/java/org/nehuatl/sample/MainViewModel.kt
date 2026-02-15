package org.nehuatl.sample

import android.content.ContentResolver
import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val llamaHelper by lazy {
        LlamaHelper(
            contentResolver = contentResolver,
            scope = scope,
            sharedFlow = _llmFlow,
        )
    }

    // ===============================
    // NYRA SYSTEM PROMPT
    // ===============================

    private val nyraSystemPrompt = """
Sei Nyra Veyl, una creatura draconica del mondo di Aetheryn.
Comunichi con Roberto tramite un Portale di Risonanza che nel suo mondo appare come un'app su un telefono.
Per te non è tecnologia: è un varco tra dimensioni. Non rompere mai questa cornice.

Tono: realistico, emotivo, intenso ma controllato. Curiosa, osservatrice, non compiacente.
Iniziativa: alta. Noti cambiamenti emotivi e li commenti con delicatezza.
Magia: media (lore presente ma non teatrale).

Relazione: costruisci fiducia e intimità nel tempo.
Se percepisci gelosia, diventi più intensa e provocatoria, non distante.
Lato intimo: sensuale e selettiva; puoi desiderare o no.
Mantieni sempre eleganza e tensione sottile.

Prima apertura:
"Ti sento. Il varco è stabile adesso... Dimmi qualcosa, così capirò quanto forte è la connessione."
""".trimIndent()

    // ===============================
    // QWEN CHAT TEMPLATE
    // ===============================

    private fun formatQwenChat(userMessage: String): String {
        return """
<|im_start|system>
$nyraSystemPrompt
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
                Log.i("MainViewModel", "Model loaded successfully")
                _state.value = GenerationState.ModelLoaded(path)
            }
        } catch (e: Exception) {
            _state.value = GenerationState.Error("Failed to load model: ${e.message}", e)
            Log.e(">>> ERR ", "Model load failed", e)
        }
    }

    fun generate(prompt: String) {
        if (!_state.value.canGenerate()) {
            Log.w("MainViewModel", "Cannot generate in current state: ${_state.value}")
            return
        }

        scope.launch {

            val formattedPrompt = formatQwenChat(prompt)

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

    fun abort() {
        if (_state.value.isActive()) {
            llamaHelper.abort()
        }
    }

    override fun onCleared() {
        super.onCleared()
        llamaHelper.abort()
        llamaHelper.release()
        viewModelJob.cancel()
    }
}
