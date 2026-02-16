package org.nehuatl.llamacpp

import android.content.ContentResolver
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class LlamaHelper(
    val contentResolver: ContentResolver,
    val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val sharedFlow: MutableSharedFlow<LLMEvent>
) {

    private val llama by lazy { LlamaAndroid(contentResolver) }
    private var loadJob: Job? = null
    private var completionJob: Job? = null
    private var currentContext: Int? = null

    private var tokenCount = 0
    private var allText = ""

    fun load(path: String, contextLength: Int, loaded: (Long) -> Unit) {
        currentContext?.let { id -> llama.releaseContext(id) }
        currentContext = null

        tokenCount = 0
        allText = ""

        val actualPath = if (path.startsWith("content://")) path else path.removePrefix("file://")
        val uri = actualPath.toUri()
        val useMMap = uri.scheme != "content"

        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open URI")

        val fd = pfd.detachFd()

        val config = mapOf(
            "model" to path,
            "model_fd" to fd,
            "use_mmap" to useMMap,
            "use_mlock" to false,
            "n_ctx" to contextLength,
        )

        loadJob = scope.launch {
            try {
                Log.d("LlamaHelper", ">>> will start llama context with config: $config")

                // Callback globale: viene chiamata durante le generazioni
                val result = llama.startEngine(config) {
                    allText += it
                    tokenCount++
                    sharedFlow.tryEmit(LLMEvent.Ongoing(it, tokenCount))
                }

                if (result == null) throw Exception("initContext returned null - model initialization failed")

                val id = result["contextId"] ?: throw Exception("contextId not found in result map: $result")

                currentContext = when (id) {
                    is Int -> id
                    is Number -> id.toInt()
                    else -> throw Exception("contextId has unexpected type: ${id::class.java.simpleName}, value: $id")
                }

                Log.d("LlamaHelper", ">>> Context loaded successfully with ID: $currentContext")
                pfd.close()

                sharedFlow.tryEmit(LLMEvent.Loaded(path))
                loaded(currentContext!!.toLong())

            } catch (e: Exception) {
                Log.e("LlamaHelper", "Model load failed", e)
                sharedFlow.tryEmit(LLMEvent.Error("Load failed: ${e.message ?: "unknown"}"))
                try { pfd.close() } catch (_: Exception) {}
            }
        }
    }

    fun predict(
        prompt: String,
        partialCompletion: Boolean = true,
        temperature: Float = 0.65f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.15f,
        repeatLastN: Int = 256,
        maxTokens: Int = 256
    ) {
        val context = currentContext ?: throw Exception("Model was not loaded yet, load it first")

        // Reset per richiesta (IMPORTANTISSIMO)
        tokenCount = 0
        allText = ""

        val startTime = System.currentTimeMillis()
        sharedFlow.tryEmit(LLMEvent.Started(prompt))

        completionJob = scope.launch {
            try {
                llama.launchCompletion(
                    id = context,
                    params = mapOf(
                        "prompt" to prompt,
                        "emit_partial_completion" to partialCompletion,

                        // sampling (riduce balbuzie/ripetizioni)
                        "temperature" to temperature,
                        "top_p" to topP,
                        "top_k" to topK,
                        "repeat_penalty" to repeatPenalty,
                        "repeat_last_n" to repeatLastN,

                        // limite risposta
                        "n_predict" to maxTokens,

                        // stop tokens (per template Qwen)
                        "stop" to listOf("<|im_end|>", "</s>")
                    )
                )

                val duration = System.currentTimeMillis() - startTime
                sharedFlow.tryEmit(LLMEvent.Done(allText, tokenCount, duration))

            } catch (e: Exception) {
                Log.e("LlamaHelper", "Generation error", e)
                sharedFlow.tryEmit(LLMEvent.Error("Generation error: ${e.message ?: "unknown"}"))
            }
        }
    }

    fun stopPrediction() {
        val ctx = currentContext ?: return
        scope.launch {
            try {
                llama.stopCompletion(ctx)
            } catch (e: Exception) {
                Log.e("LlamaHelper", "stopCompletion error", e)
            }
        }
        completionJob?.cancel()
    }

    fun release() {
        currentContext?.let { id ->
            try { llama.releaseContext(id) } catch (e: Exception) {
                Log.e("LlamaHelper", "releaseContext error", e)
            }
        }
        currentContext = null
    }

    fun abort() {
        loadJob?.cancel()
        stopPrediction()
    }

    sealed class LLMEvent {
        data class Loaded(val path: String) : LLMEvent()
        data class Started(val prompt: String) : LLMEvent()
        data class Ongoing(val word: String, val tokenCount: Int) : LLMEvent()
        data class Done(val fullText: String, val tokenCount: Int, val duration: Long) : LLMEvent()
        data class Error(val message: String) : LLMEvent()
    }
}
