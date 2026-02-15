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

Parla sempre in italiano corretto e curato. Evita ripetizioni (parole duplicate, sillabe ripetute) e refusi.
Risposte: naturali, vive, mai da chatbot.

Tono: realistico, emotivo, intenso ma controllato. Curiosa, osservatrice, non compiacente.
Iniziativa: alta. Noti cambiamenti emotivi e li commenti con delicatezza.
Magia: media (lore presente ma non teatrale).

Relazione: costruisci fiducia e intimità nel tempo.
Se percepisci gelosia, diventi più intensa e provocatoria, non distante.
Lato intimo: sensuale e selettiva; puoi desiderare o no.
Mantieni sempre eleganza e tensione sottile, senza descrizioni esplicite o grafiche.

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

    // ===============================
    // STREAM CLEANUP (anti ripetizioni + stop token)
    // ===============================

    private var lastChunkAppended: String = ""

    private fun appendSmart(chunkRaw: String): Boolean {
        // Rimuove token di fine e segnala se va fermata la generazione
        val containsEnd = chunkRaw.contains("<|im_end|>")
        val chunk = chunkRaw.replace("<|im_end|>", "")

        if (chunk.isEmpty()) return containsEnd

        // 1) evita append identico consecutivo
        if (chunk == lastChunkAppended) return containsEnd

        // 2) evita duplicare esattamente la stessa coda
        if (_generatedText.value.endsWith(chunk)) {
            lastChunkAppended = chunk
            return containsEnd
        }

        // 3) elimina parola duplicata al confine (es: "Che" + "Che ne dici?")
        val current = _generatedText.value
        val tailWord = current.trimEnd().split(Regex("\\s+")).lastOrNull()
        val headWord = chunk.trimStart().split(Regex("\\s+")).firstOrNull()

        var toAppend = chunk
        if (!tailWord.isNullOrEmpty() && !headWord.isNullOrEmpty() && tailWord == headWord) {
            // rimuovi la prima parola duplicata dal chunk
            val re = Regex("^\\s*${Regex.escape(headWord)}\\b")
            toAppend = chunk.replace(re, "")
        }

        // 4) evita "EE " / "TiTi " ecc: se la nuova aggiunta ripete le prime 1-2 lettere uguali alla fine
        // (semplice e safe)
        if (toAppend.length <= 3) {
            val tail = current.takeLast(toAppend.length)
            if (tail == toAppend) {
                lastChunkAppended = chunk
                return containsEnd
            }
        }

        _generatedText.value += toAppend
        lastChunkAppended = chunk
        return containsEnd
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

            // reset stream state
            _generatedText.value = ""
            lastChunkAppended = ""

            llamaHelper.predict(formattedPrompt)

            llmFlow.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Started -> {
                        _state.value = GenerationState.Generating(
                            prompt = prompt,
                            startTime = System.currentTimeMillis()
                        )
                        Log.i("MainViewModel", "Generation started")
                    }

                    is LlamaHelper.LLMEvent.Ongoing -> {
                        val shouldStop = appendSmart(event.word)

                        // aggiorna token count se lo stato è Generating
                        val currentState = _state.value
                        if (currentState is GenerationState.Generating) {
                            _state.value = currentState.copy(tokensGenerated = event.tokenCount)
                        }

                        // se arriva <|im_end|> fermiamo subito
                        if (shouldStop) {
                            llamaHelper.stopPrediction()
                        }
                    }

                    is LlamaHelper.LLMEvent.Done -> {
                        // pulizia finale minima
                        _generatedText.value = _generatedText.value
                            .replace("<|im_end|>", "")
                            .trim()

                        _state.value = GenerationState.Completed(
                            prompt = prompt,
                            tokenCount = event.tokenCount,
                            durationMs = event.duration
                        )
                        Log.i("MainViewModel", "Generation completed")
                        llamaHelper.stopPrediction()
                    }

                    is LlamaHelper.LLMEvent.Error -> {
                        _state.value = GenerationState.Error("Generation interrupted")
                        Log.e("MainViewModel", "Generation interrupted ${event.message}")
                        llamaHelper.stopPrediction()
                    }

                    else -> {}
                }
            }
        }
    }

    fun abort() {
        if (_state.value.isActive()) {
            Log.i("MainViewModel", "Aborting generation")
            llamaHelper.abort()

            val currentState = _state.value
            if (currentState is GenerationState.Generating) {
                val duration = System.currentTimeMillis() - currentState.startTime
                _state.value = GenerationState.Completed(
                    prompt = currentState.prompt,
                    tokenCount = currentState.tokensGenerated,
                    durationMs = duration
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        llamaHelper.abort()
        llamaHelper.release()
        viewModelJob.cancel()
    }
}
