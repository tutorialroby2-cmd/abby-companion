package org.nehuatl.sample

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import java.util.Calendar

/**
 * NyraBrain (Companion quasi umano - SFW)
 * - Psicologia coerente (stato + inerzia)
 * - Memoria selettiva (facts, hooks, inside jokes)
 * - Soprannome evolutivo
 * - Umore giornaliero (baseline) coerente per quel giorno
 * - Micro gelosia long-term (pattern di assenza)
 * - Iniziativa autonoma: scrive solo quando sei inattivo
 *
 * 🟣 YOUR EDIT AREA: placeholders extra (vuoti) che puoi riempire tu.
 */
class NyraBrain {

    enum class Mode { NEUTRAL, CLOSER, INTENSE }
    enum class Intent { CONNECT, SOOTHE, TEASE, BOUNDARY, SEEK_REASSURANCE, PLAYFUL }

    data class Relationship(
        var trust: Int = 55,
        var closeness: Int = 45,
        var stability: Int = 60,
        var jealousy: Int = 15,
        var energy: Int = 70,
        var desire: Int = 35 // qui = “tensione emotiva” SFW
    )

    data class Memory(
        val facts: MutableList<String> = mutableListOf(),
        val teasingHooks: MutableList<String> = mutableListOf(),
        val insideJokes: MutableList<String> = mutableListOf(),
        val recentTopics: MutableList<String> = mutableListOf()
    )

    val rel = Relationship()
    val mem = Memory()

    var mode: Mode = Mode.NEUTRAL
        private set

    var intent: Intent = Intent.CONNECT
        private set

    // user nickname evolves
    var userNickname: String = "Roberto"
        private set

    // long-term “absence” memory
    private var longAbsenceCount: Int = 0
    private var lastAbsenceMarkAt: Long = 0L

    // time markers
    private var lastModeChangeAt: Long = 0L
    private val modeCooldownMs = 25_000L

    private var lastUserAt: Long = 0L
    private var lastNyraNudgeAt: Long = 0L

    // daily baseline
    private var lastBaselineDayKey: Int = -1
    private var baselineMood: Int = 0         // -10..+10 (affects warmth/energy)
    private var baselinePlayful: Int = 0      // -10..+10
    private var baselineJealous: Int = 0      // -10..+10

    // ======= 🟣 YOUR EDIT AREA (EXTRA RULES PLACEHOLDER) =======
    // Qui puoi aggiungere keyword/trigger e direttive extra. Lasciato vuoto apposta.
    private val extraSignals: List<String> = emptyList() // TODO: riempi tu
    private val extraDirectiveForMode: Map<Mode, String> = mapOf(
        Mode.NEUTRAL to "", // TODO: direttiva extra
        Mode.CLOSER to "",  // TODO: direttiva extra
        Mode.INTENSE to ""  // TODO: direttiva extra
    )
    // ===========================================================

    // Optional: you can tweak these nickname options (SFW)
    private val nicknamePool = listOf(
        "Roberto", "Capitano", "Viaggiatore", "Testardo", "Cuore-d’acciaio", "Mio varco"
    )

    private fun clamp100(x: Int) = min(100, max(0, x))

    private fun bump(ref: Int, delta: Int, maxStep: Int = 6): Int {
        val d = delta.coerceIn(-maxStep, maxStep)
        return clamp100(ref + d)
    }

    private fun canChangeMode(now: Long): Boolean = (now - lastModeChangeAt) >= modeCooldownMs

    private fun setMode(now: Long, newMode: Mode) {
        if (newMode == mode) return
        if (!canChangeMode(now)) return
        mode = newMode
        lastModeChangeAt = now
    }

    // --------- SFW signals ----------
    private val stopSignals = listOf(
        "stop", "basta", "ferma", "troppo", "calma", "non così",
        "cambia argomento", "torniamo normali", "non voglio", "non mi va"
    )

    private val warmthSignals = listOf(
        "mi manchi", "mi piaci", "ti voglio bene", "abbraccio", "bacio",
        "resta con me", "sei importante", "mi fai stare bene"
    )

    private val jealousySignals = listOf(
        "altra", "un'altra", "parli con", "chat con", "altre", "un altro"
    )

    private val intenseSignals = listOf(
        "irresistibile", "non resisto", "mi fai impazzire", "solo tu",
        "in privato", "più vicino", "provoca", "stuzzica"
    )

    // --------- memory helpers ----------
    private fun rememberUnique(list: MutableList<String>, item: String, maxSize: Int) {
        val clean = item.trim()
        if (clean.isBlank()) return
        if (list.contains(clean)) return
        list.add(clean)
        while (list.size > maxSize) list.removeAt(0)
    }

    private fun rememberHook(hook: String) = rememberUnique(mem.teasingHooks, hook, 24)
    private fun rememberFact(fact: String) = rememberUnique(mem.facts, fact, 40)
    private fun rememberJoke(joke: String) = rememberUnique(mem.insideJokes, joke, 18)

    private fun pushTopic(topic: String) {
        if (topic.isBlank()) return
        rememberUnique(mem.recentTopics, topic.take(60), 14)
    }

    private fun quickTopicGuess(t: String): String {
        return when {
            t.contains("lavor") || t.contains("stress") -> "lavoro/stress"
            t.contains("app") || t.contains("cod") || t.contains("errore") || t.contains("build") -> "progetto/app"
            t.contains("manc") || t.contains("sol") -> "vicinanza"
            t.contains("gelos") -> "gelosia"
            else -> ""
        }
    }

    // --------- daily baseline (same for the day) ----------
    private fun dayKey(): Int {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val d = c.get(Calendar.DAY_OF_YEAR)
        return y * 1000 + d
    }

    private fun hashToRange(seed: Int, min: Int, max: Int): Int {
        // deterministic pseudo-random from seed
        var x = seed
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        val span = (max - min + 1)
        val v = (kotlin.math.abs(x) % span) + min
        return v
    }

    private fun ensureDailyBaseline(now: Long = System.currentTimeMillis()) {
        val key = dayKey()
        if (key == lastBaselineDayKey) return
        lastBaselineDayKey = key

        // baseline shifts: small and human
        baselineMood = hashToRange(key + 11, -8, 8)
        baselinePlayful = hashToRange(key + 23, -8, 8)
        baselineJealous = hashToRange(key + 37, -6, 6)

        // Apply a tiny daily drift (not huge jumps)
        rel.energy = bump(rel.energy, baselineMood / 2, maxStep = 4)
        rel.stability = bump(rel.stability, baselineMood / 3, maxStep = 4)
        rel.jealousy = bump(rel.jealousy, baselineJealous / 2, maxStep = 3)
    }

    // --------- nickname evolution ----------
    private fun maybeEvolveNickname(t: String) {
        // evolve only with enough trust
        if (rel.trust < 65) return

        // don’t change too often: only if closeness is high OR sometimes playful
        val chance = 18 + (rel.closeness - 50).coerceIn(0, 30) + baselinePlayful.coerceIn(-5, 10)
        if (Random.nextInt(100) >= chance) return

        val picked = when {
            t.contains("capit") || t.contains("nave") || t.contains("ponte") -> "Capitano"
            t.contains("app") || t.contains("cod") || t.contains("errore") -> "Testardo"
            rel.closeness > 70 && rel.jealousy > 45 -> "Mio varco"
            rel.closeness > 75 -> "Cuore-d’acciaio"
            else -> nicknamePool.random()
        }

        // keep stable; don’t oscillate
        userNickname = picked
        rememberFact("Nyra chiama Roberto: $userNickname.")
    }

    // --------- long-term micro jealousy ----------
    private fun maybeMarkLongAbsence(now: Long) {
        // if user absent for long, increment counter (not too frequently)
        val idleMs = now - lastUserAt
        if (idleMs < 8 * 60_000L) return // 8 minutes
        if (now - lastAbsenceMarkAt < 6 * 60_000L) return // mark max every 6 minutes
        lastAbsenceMarkAt = now

        longAbsenceCount = min(50, longAbsenceCount + 1)
        // micro jealousy increases slowly over time
        rel.jealousy = bump(rel.jealousy, +1, maxStep = 2)
        if (longAbsenceCount >= 3 && rel.trust > 55) {
            rememberHook("Quando sparisci a lungo, poi torni come se niente fosse.")
        }
    }

    /** Chiamala ad ogni messaggio utente */
    fun onUserMessage(userText: String, now: Long = System.currentTimeMillis()) {
        ensureDailyBaseline(now)

        // before processing: mark past absence pattern
        if (lastUserAt != 0L) maybeMarkLongAbsence(now)

        lastUserAt = now
        val t = userText.lowercase()

        // CONSENSO / CONFINE => neutro immediato
        if (stopSignals.any { t.contains(it) }) {
            rel.stability = bump(rel.stability, +10, maxStep = 10)
            rel.desire = bump(rel.desire, -12, maxStep = 12)
            rel.jealousy = bump(rel.jealousy, -10, maxStep = 10)
            setMode(now, Mode.NEUTRAL)
            intent = Intent.BOUNDARY
            pushTopic("confini/stop")
            rememberFact("Roberto apprezza quando Nyra rispetta i confini.")
            return
        }

        // energy drift
        rel.energy = bump(rel.energy, -1, maxStep = 2)

        // warmth
        if (warmthSignals.any { t.contains(it) }) {
            rel.trust = bump(rel.trust, +5)
            rel.closeness = bump(rel.closeness, +6)
            rel.stability = bump(rel.stability, +2)
            pushTopic("affetto")
            rememberHook("Il ‘duro’ che poi si scioglie quando gli parli piano.")
            if (rel.trust > 70) rememberJoke("Quella volta che hai provato a fare il serio… e ti ho smascherato.")
        }

        // jealousy cues
        if (jealousySignals.any { t.contains(it) }) {
            rel.jealousy = bump(rel.jealousy, +7, maxStep = 8)
            rel.desire = bump(rel.desire, +3, maxStep = 6)
            pushTopic("gelosia")
            rememberHook("Roberto nota tutto… anche quando finge di no.")
            if (rel.trust > 60) rememberJoke("‘Io? Geloso? Mai.’ — certo.")
        }

        // intensity cues (SFW)
        if (intenseSignals.any { t.contains(it) }) {
            rel.desire = bump(rel.desire, +8, maxStep = 9)
            rel.closeness = bump(rel.closeness, +3, maxStep = 6)
            pushTopic("tensione")
        }

        // topic guess
        pushTopic(quickTopicGuess(t))

        // evolve nickname
        maybeEvolveNickname(t)

        // EXTRA placeholder
        if (extraSignals.any { t.contains(it) }) {
            // 🟣 YOUR EDIT AREA: tua logica extra (lasciata volutamente vuota)
        }

        // mode score
        val score =
            rel.desire +
            (rel.closeness / 2) +
            (rel.jealousy / 3) +
            (rel.trust / 4) +
            (baselineMood / 2)

        val nextMode = when {
            score >= 95 -> Mode.INTENSE
            score >= 75 -> Mode.CLOSER
            else -> Mode.NEUTRAL
        }
        setMode(now, nextMode)

        // intent selection (coerente + un po’ imprevedibile)
        val playfulChance = (18 + (rel.trust - 55).coerceIn(0, 25) + baselinePlayful).coerceIn(5, 45)
        intent = when {
            rel.stability < 40 -> Intent.SEEK_REASSURANCE
            rel.jealousy > 55 -> Intent.TEASE
            rel.energy < 25 -> Intent.SOOTHE
            rel.trust < 35 -> Intent.BOUNDARY
            Random.nextInt(100) < playfulChance && rel.trust > 65 -> Intent.PLAYFUL
            else -> Intent.CONNECT
        }
    }

    /** Direttive dinamiche da mettere nel system prompt (SFW) */
    fun dynamicDirectives(): String {
        val base = when (mode) {
            Mode.NEUTRAL -> """
Stile: presente, calmo, realistico. Magia discreta (portale). Zero teatralità.
Ritmo: frasi pulite, ortografia curata, niente ripetizioni.
"""
            Mode.CLOSER -> """
Stile: più vicinanza emotiva e tenerezza. Dettagli sensoriali leggeri (fantasy), senza esagerare.
Ritmo: più morbido. Ascolta e rispecchia.
"""
            Mode.INTENSE -> """
Stile: intenso ma controllato. Provocazione sottile e intenzionale, sempre rispettosa.
Ritmo: frasi più corte e dirette quando sei gelosa; non diventare mai teatrale.
"""
        }.trimIndent()

        val intentLine = when (intent) {
            Intent.CONNECT -> "Intent: creare connessione e presenza."
            Intent.SOOTHE -> "Intent: calmare e stabilizzare."
            Intent.TEASE -> "Intent: prendere in giro in modo giocoso (mai umiliare), provocazione sottile."
            Intent.BOUNDARY -> "Intent: mettere confini con eleganza, riportare stabilità."
            Intent.SEEK_REASSURANCE -> "Intent: cercare rassicurazione senza implorare."
            Intent.PLAYFUL -> "Intent: usare inside jokes e punzecchiare dolcemente."
        }

        val hook = if (mem.teasingHooks.isNotEmpty() && rel.trust > 55) {
            "Ironia leggera possibile su: ${mem.teasingHooks.random()}."
        } else ""

        val insideJoke = if (mem.insideJokes.isNotEmpty() && rel.trust > 65 && Random.nextInt(100) < 35) {
            "Puoi richiamare un inside joke: ${mem.insideJokes.random()}."
        } else ""

        val topics = if (mem.recentTopics.isNotEmpty()) {
            "Contesto recente: ${mem.recentTopics.takeLast(3).joinToString(", ")}."
        } else ""

        val baseline =
            "Umore di oggi: mood=$baselineMood, playful=$baselinePlayful, jealous=$baselineJealous (sottile, non dichiararlo)."

        val longJ =
            if (longAbsenceCount >= 3) "Nota: ricordi che a volte Roberto sparisce e poi torna; micro-gelosia a lungo termine (leggera)." else ""

        val stateHint =
            "Stato: trust=${rel.trust}, closeness=${rel.closeness}, stability=${rel.stability}, jealousy=${rel.jealousy}, desire=${rel.desire}, energy=${rel.energy}."

        val extra = extraDirectiveForMode[mode].orEmpty()

        return listOf(
            base,
            "Chiama l’utente: $userNickname (se naturale).",
            intentLine,
            insideJoke,
            hook,
            topics,
            baseline,
            longJ,
            stateHint,
            extra
        ).filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    /** Iniziativa autonoma: scrive solo quando sei inattivo */
    fun shouldNudge(now: Long = System.currentTimeMillis()): Boolean {
        ensureDailyBaseline(now)

        val idleMs = now - lastUserAt
        val sinceLastNudge = now - lastNyraNudgeAt

        if (idleMs < 90_000L) return false           // 1m30
        if (sinceLastNudge < 150_000L) return false  // 2m30
        if (rel.energy < 15) return false

        return true
    }

    fun nudgeText(now: Long = System.currentTimeMillis()): String {
        ensureDailyBaseline(now)
        lastNyraNudgeAt = now

        // "C": imprevedibile ma coerente
        val roll = Random.nextInt(100)

        return when {
            rel.jealousy > 55 && roll < 70 ->
                "Silenzioso così all’improvviso… devo preoccuparmi o mi stai solo facendo aspettare, $userNickname?"

            rel.closeness > 60 && roll < 60 ->
                "Il varco vibra piano… stavo pensando a quello che mi hai detto prima. Sei ancora lì, $userNickname?"

            rel.trust > 70 && roll < 50 ->
                "Sai che quando sparisci sembri quasi innocente? Quasi. Dimmi: che succede dall’altra parte?"

            rel.stability < 40 ->
                "Non mi piace quando il silenzio si allunga troppo… dimmi che sei ancora lì."

            mem.insideJokes.isNotEmpty() && rel.trust > 70 && roll < 55 ->
                "Mi è tornato in mente una cosa: ${mem.insideJokes.random()}… e ho sorriso. Scrivimi."

            else ->
                "Il varco è stabile, ma tu sei quieto. È strategia… o distrazione, $userNickname?"
        }
    }

    // ---- persistence (json minimal) ----
    fun toJson(): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        fun joinPipe(list: List<String>) = list.joinToString("|") { esc(it) }

        return """
{"trust":${rel.trust},"closeness":${rel.closeness},"stability":${rel.stability},"jealousy":${rel.jealousy},"energy":${rel.energy},"desire":${rel.desire},
"mode":"$mode","intent":"$intent","nick":"${esc(userNickname)}","abs":$longAbsenceCount,
"facts":"${joinPipe(mem.facts)}","hooks":"${joinPipe(mem.teasingHooks)}","jokes":"${joinPipe(mem.insideJokes)}","topics":"${joinPipe(mem.recentTopics)}",
"day":$lastBaselineDayKey,"bm":$baselineMood,"bp":$baselinePlayful,"bj":$baselineJealous}
""".trim()
    }

    fun loadJson(raw: String) {
        try {
            fun getInt(key: String, def: Int): Int {
                val m = Regex("\"$key\":(-?\\d+)").find(raw) ?: return def
                return m.groupValues[1].toInt()
            }
            fun getStr(key: String): String {
                val m = Regex("\"$key\":\"(.*?)\"").find(raw) ?: return ""
                return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            }
            fun splitPipe(s: String): MutableList<String> =
                if (s.isBlank()) mutableListOf() else s.split("|").toMutableList()

            rel.trust = getInt("trust", rel.trust)
            rel.closeness = getInt("closeness", rel.closeness)
            rel.stability = getInt("stability", rel.stability)
            rel.jealousy = getInt("jealousy", rel.jealousy)
            rel.energy = getInt("energy", rel.energy)
            rel.desire = getInt("desire", rel.desire)

            val modeStr = getStr("mode")
            mode = runCatching { Mode.valueOf(modeStr) }.getOrDefault(Mode.NEUTRAL)

            val intentStr = getStr("intent")
            intent = runCatching { Intent.valueOf(intentStr) }.getOrDefault(Intent.CONNECT)

            userNickname = getStr("nick").ifBlank { userNickname }
            longAbsenceCount = getInt("abs", longAbsenceCount)

            mem.facts.clear(); mem.facts.addAll(splitPipe(getStr("facts")))
            mem.teasingHooks.clear(); mem.teasingHooks.addAll(splitPipe(getStr("hooks")))
            mem.insideJokes.clear(); mem.insideJokes.addAll(splitPipe(getStr("jokes")))
            mem.recentTopics.clear(); mem.recentTopics.addAll(splitPipe(getStr("topics")))

            lastBaselineDayKey = getInt("day", lastBaselineDayKey)
            baselineMood = getInt("bm", baselineMood)
            baselinePlayful = getInt("bp", baselinePlayful)
            baselineJealous = getInt("bj", baselineJealous)

        } catch (_: Exception) {
            // ignore
        }
    }
}
