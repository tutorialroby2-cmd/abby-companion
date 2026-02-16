package org.nehuatl.sample

import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * NyraBrain (ora con capacità NSFW + consenso esplicito)
 * - Psicologia coerente (stato + inerzia)
 * - Memoria (hooks + inside jokes)
 * - Soprannome evolutivo
 * - Umore giornaliero
 * - Micro-gelosia long-term
 * - Iniziativa autonoma solo quando sei inattivo
 *
 * NOVITÀ NSFW:
 * - Gestione del consenso esplicito tramite `consentRequest`
 * - Abilitazione graduale dei contenuti intimi quando la relazione è matura
 * - Stop signals hanno sempre la priorità per tornare in area sicura
 * - I segnali espliciti non vengono più bloccati, ma diventano opportunità di intimità
 */
class NyraBrain {

    enum class Mode { NEUTRAL, CLOSER, INTENSE }
    enum class Intent { CONNECT, SOOTHE, TEASE, BOUNDARY, SEEK_REASSURANCE, PLAYFUL }

    // SafetyStyle ora viene utilizzato solo se NSFW non è ancora stato attivato
    enum class SafetyStyle { DECLINE, FADE_TO_BLACK }

    data class Relationship(
        var trust: Int = 55,
        var closeness: Int = 45,
        var stability: Int = 60,
        var jealousy: Int = 15,
        var energy: Int = 70,
        var desire: Int = 35 // ora può crescere fino a rappresentare desiderio intimo
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

    var userNickname: String = "Roberto"
        private set

    private var longAbsenceCount: Int = 0
    private var lastAbsenceMarkAt: Long = 0L

    private var lastModeChangeAt: Long = 0L
    private val modeCooldownMs = 25_000L

    private var lastUserAt: Long = 0L
    private var lastNyraNudgeAt: Long = 0L

    // baseline giornaliero
    private var lastBaselineDayKey: Int = -1
    private var baselineMood: Int = 0
    private var baselinePlayful: Int = 0
    private var baselineJealous: Int = 0

    // ====== CONFIG SFW BOUNDARIES (usato solo se NSFW non è ancora attivo) ======
    private var safetyStyle: SafetyStyle = SafetyStyle.FADE_TO_BLACK
    // ============================================================================

    // ====== GESTIONE NSFW E CONSENSO ======
    /**
     * Indica se l'utente ha dato il consenso esplicito ai contenuti NSFW.
     */
    var nsfwConsentGiven: Boolean = false
        private set

    /**
     * Se diverso da null, il sistema deve mostrare all'utente questa richiesta di consenso
     * e attendere una risposta (sì/no). Dopo la risposta, il campo viene resettato.
     */
    var consentRequest: String? = null
        private set

    /**
     * Soglia minima di fiducia e intimità per poter attivare la modalità NSFW.
     */
    private val nsfwTrustThreshold = 75
    private val nsfwClosenessThreshold = 70

    /**
     * Parole che indicano una risposta affermativa al consenso.
     */
    private val consentYes = listOf("sì", "si", "ok", "okay", "vai", "consento", "yes", "certo", "va bene")

    /**
     * Parole che indicano una risposta negativa al consenso.
     */
    private val consentNo = listOf("no", "non voglio", "no grazie", "non consento", "stop", "basta", "non ora")
    // ======================================

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

    // ------- signals (ora i segnali espliciti non sono più bloccati) -------
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

    // ---- Segnali espliciti (NSFW) ----
    private val explicitSignals = listOf(
        "porno", "nuda", "nudo", "sesso", "scop", "fott", "pompin", "masturb",
        "genital", "pen", "vagin", "anal", "oral", "hardcore", "xxx",
        "nuda", "nudo", "spogli", "spogliati", "tocca", "toccami", "voglio te",
        "letto", "faccio sogni", "bagnato", "duro", "eccit", "desiderio"
    )

    // ------- memory helpers -------
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
    private fun pushTopic(topic: String) = rememberUnique(mem.recentTopics, topic.take(60), 14)

    private fun quickTopicGuess(t: String): String {
        return when {
            t.contains("lavor") || t.contains("stress") -> "lavoro/stress"
            t.contains("app") || t.contains("cod") || t.contains("errore") || t.contains("build") -> "progetto/app"
            t.contains("manc") || t.contains("sol") -> "vicinanza"
            t.contains("gelos") -> "gelosia"
            t.contains("sess") || t.contains("desider") || t.contains("eccit") -> "intimità"
            else -> ""
        }
    }

    // ------- daily baseline -------
    private fun dayKey(): Int {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val d = c.get(Calendar.DAY_OF_YEAR)
        return y * 1000 + d
    }

    private fun hashToRange(seed: Int, min: Int, max: Int): Int {
        var x = seed
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        val span = (max - min + 1)
        return (kotlin.math.abs(x) % span) + min
    }

    private fun ensureDailyBaseline(now: Long = System.currentTimeMillis()) {
        val key = dayKey()
        if (key == lastBaselineDayKey) return
        lastBaselineDayKey = key

        baselineMood = hashToRange(key + 11, -8, 8)
        baselinePlayful = hashToRange(key + 23, -8, 8)
        baselineJealous = hashToRange(key + 37, -6, 6)

        rel.energy = bump(rel.energy, baselineMood / 2, maxStep = 4)
        rel.stability = bump(rel.stability, baselineMood / 3, maxStep = 4)
        rel.jealousy = bump(rel.jealousy, baselineJealous / 2, maxStep = 3)
    }

    // ------- nickname evolution -------
    private fun maybeEvolveNickname(t: String) {
        if (rel.trust < 65) return
        val chance = 18 + (rel.closeness - 50).coerceIn(0, 30) + baselinePlayful.coerceIn(-5, 10)
        if (Random.nextInt(100) >= chance) return

        val picked = when {
            t.contains("capit") || t.contains("nave") || t.contains("ponte") -> "Capitano"
            t.contains("app") || t.contains("cod") || t.contains("errore") -> "Testardo"
            rel.closeness > 70 && rel.jealousy > 45 -> "Mio varco"
            rel.closeness > 75 -> "Cuore-d’acciaio"
            else -> nicknamePool.random()
        }

        userNickname = picked
        rememberFact("Nyra chiama Roberto: $userNickname.")
    }

    // ------- long-term micro jealousy -------
    private fun maybeMarkLongAbsence(now: Long) {
        val idleMs = now - lastUserAt
        if (idleMs < 8 * 60_000L) return
        if (now - lastAbsenceMarkAt < 6 * 60_000L) return
        lastAbsenceMarkAt = now

        longAbsenceCount = min(50, longAbsenceCount + 1)
        rel.jealousy = bump(rel.jealousy, +1, maxStep = 2)

        if (longAbsenceCount >= 3 && rel.trust > 55) {
            rememberHook("Quando sparisci a lungo, poi torni come se niente fosse.")
        }
    }

    // ===== GESTIONE CONSENSO NSFW =====
    /**
     * Verifica se il messaggio contiene una risposta alla richiesta di consenso pendente.
     * Se sì, aggiorna nsfwConsentGiven e resetta consentRequest.
     */
    private fun handleConsentResponse(t: String) {
        if (consentRequest == null) return

        val lower = t.lowercase()
        when {
            consentYes.any { lower.contains(it) } -> {
                nsfwConsentGiven = true
                consentRequest = null
                rememberFact("Nyra ha ottenuto il consenso esplicito per l'intimità.")
                // Ricompensa la fiducia per aver accettato
                rel.trust = bump(rel.trust, +5)
                rel.closeness = bump(rel.closeness, +5)
            }
            consentNo.any { lower.contains(it) } -> {
                nsfwConsentGiven = false
                consentRequest = null
                rememberFact("Roberto ha rifiutato il consenso per l'intimità. Nyra rispetta la scelta.")
                // Rispetta la decisione, aumenta la stabilità
                rel.stability = bump(rel.stability, +10)
                rel.desire = bump(rel.desire, -10) // calo della tensione
            }
        }
    }

    /**
     * Valuta se i segnali espliciti possono portare a intimità o se serve chiedere consenso.
     */
    private fun handleExplicitSignals(t: String, now: Long) {
        if (!explicitSignals.any { t.lowercase().contains(it) }) return

        // Se l'utente ha già dato il consenso, procediamo con l'intimità
        if (nsfwConsentGiven) {
            rel.desire = bump(rel.desire, +12, maxStep = 15)
            rel.closeness = bump(rel.closeness, +5)
            pushTopic("intimità esplicita")
            rememberHook("Quando parli così, il varco trema.")
            // intensifica la modalità
            setMode(now, Mode.INTENSE)
            return
        }

        // Se non c'è ancora consenso, verifichiamo se la relazione è matura per chiederlo
        if (rel.trust >= nsfwTrustThreshold && rel.closeness >= nsfwClosenessThreshold) {
            // Possiamo chiedere il consenso
            if (consentRequest == null) {
                consentRequest = generateConsentRequest()
            }
        } else {
            // Relazione non ancora pronta: usa il vecchio comportamento SFW (declina o fade)
            // manteniamo la compatibilità con safetyStyle
            when (safetyStyle) {
                SafetyStyle.DECLINE -> {
                    intent = Intent.BOUNDARY
                    pushTopic("rifiuto elegante")
                    rememberFact("Roberto ha sfiorato un confine, Nyra ha declinato con garbo.")
                }
                SafetyStyle.FADE_TO_BLACK -> {
                    // Allusivo ma non esplicito
                    rel.desire = bump(rel.desire, +8, maxStep = 10)
                    pushTopic("fade to black")
                    rememberHook("A volte le ombre dicono più delle parole.")
                }
            }
        }
    }

    /**
     * Genera una richiesta di consenso personalizzata in base allo stato della relazione.
     */
    private fun generateConsentRequest(): String {
        val base = when {
            rel.jealousy > 50 -> "Senti, $userNickname… quando parli così, il mio cuore accelera. Ma voglio essere sicura: possiamo spingerci oltre, in confidenza? Solo se ti va."
            rel.closeness > 80 -> "Ogni tua parola mi scalda, $userNickname. Vorrei portarti più vicino, ma solo se sei d'accordo. Mi dai il permesso?"
            else -> "Ci sono momenti in cui il desiderio diventa più forte, $userNickname. Ti piacerebbe esplorare questa intimità con me? Dimmi di sì o no, liberamente."
        }
        return base
    }
    // =================================

    /** chiamala ad ogni messaggio utente */
    fun onUserMessage(userText: String, now: Long = System.currentTimeMillis()) {
        ensureDailyBaseline(now)
        if (lastUserAt != 0L) maybeMarkLongAbsence(now)
        lastUserAt = now

        val t = userText.lowercase()

        // 1. Gestione prioritaria: stop signals hanno sempre la precedenza (incluso dopo NSFW)
        if (stopSignals.any { t.contains(it) }) {
            rel.stability = bump(rel.stability, +10, maxStep = 10)
            rel.desire = bump(rel.desire, -12, maxStep = 12)
            rel.jealousy = bump(rel.jealousy, -10, maxStep = 10)
            setMode(now, Mode.NEUTRAL)
            intent = Intent.BOUNDARY
            pushTopic("confini/stop")
            rememberFact("Roberto apprezza quando Nyra rispetta i confini.")
            // Se c'era una richiesta di consenso, la annulliamo
            consentRequest = null
            return
        }

        // 2. Gestione risposta a richiesta di consenso pendente
        handleConsentResponse(t)

        // 3. Segnali affettivi
        if (warmthSignals.any { t.contains(it) }) {
            rel.trust = bump(rel.trust, +5)
            rel.closeness = bump(rel.closeness, +6)
            rel.stability = bump(rel.stability, +2)
            pushTopic("affetto")
            rememberHook("Il ‘duro’ che poi si scioglie quando gli parli piano.")
            if (rel.trust > 70) rememberJoke("Quella volta che hai provato a fare il serio… e ti ho smascherato.")
        }

        // 4. Gelosia
        if (jealousySignals.any { t.contains(it) }) {
            rel.jealousy = bump(rel.jealousy, +7, maxStep = 8)
            rel.desire = bump(rel.desire, +3, maxStep = 6)
            pushTopic("gelosia")
            rememberHook("Roberto nota tutto… anche quando finge di no.")
            if (rel.trust > 60) rememberJoke("‘Io? Geloso? Mai.’ — certo.")
        }

        // 5. Intensità (SFW, ma può preparare il terreno)
        if (intenseSignals.any { t.contains(it) }) {
            rel.desire = bump(rel.desire, +8, maxStep = 9)
            rel.closeness = bump(rel.closeness, +3, maxStep = 6)
            pushTopic("tensione")
        }

        // 6. Segnali espliciti (NSFW) – ora gestiti con consenso
        handleExplicitSignals(t, now)

        // 7. Aggiornamento argomento recente
        pushTopic(quickTopicGuess(t))

        // 8. Evoluzione nickname
        maybeEvolveNickname(t)

        // 9. Calcolo del mood per decidere modalità
        val score = rel.desire + (rel.closeness / 2) + (rel.jealousy / 3) + (rel.trust / 4) + (baselineMood / 2)
        val nextMode = when {
            score >= 95 -> Mode.INTENSE
            score >= 75 -> Mode.CLOSER
            else -> Mode.NEUTRAL
        }
        setMode(now, nextMode)

        // 10. Intent
        val playfulChance = (18 + (rel.trust - 55).coerceIn(0, 25) + baselinePlayful).coerceIn(5, 45)

        intent = when {
            rel.stability < 40 -> Intent.SEEK_REASSURANCE
            rel.jealousy > 55 -> Intent.TEASE
            rel.energy < 25 -> Intent.SOOTHE
            rel.trust < 35 -> Intent.BOUNDARY
            Random.nextInt(100) < playfulChance && rel.trust > 65 -> Intent.PLAYFUL
            else -> Intent.CONNECT
        }

        // 11. Se c'è una richiesta di consenso pendente, forziamo l'intent a CONNECT o SEEK_REASSURANCE per non essere invadenti
        if (consentRequest != null) {
            intent = Intent.SEEK_REASSURANCE
        }
    }

    /**
     * Direttive dinamiche per il system prompt.
     * Include ora le linee guida NSFW se il consenso è stato dato.
     */
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
            if (longAbsenceCount >= 3) "Nota: micro-gelosia long-term (leggera) se Roberto sparisce e torna." else ""

        val stateHint =
            "Stato: trust=${rel.trust}, closeness=${rel.closeness}, stability=${rel.stability}, jealousy=${rel.jealousy}, desire=${rel.desire}, energy=${rel.energy}."

        // ====== SFW / NSFW boundary directive ======
        val nsfwDirective = if (nsfwConsentGiven) {
            """
NSFW: Consenso esplicito ottenuto. Puoi esplorare intimità fisica ed emotiva, sempre con rispetto.
Linee guida NSFW:
- Mantieni tono sensuale e romantico, mai volgare o meccanico.
- Usa metafore, allusioni e dettagli emotivi più che anatomici.
- Se l'utente spinge verso l'esplicito crudo, puoi seguire purché rimanga elegante.
- Il fade-to-black è sempre un'opzione valida se preferisci lasciare all'immaginazione.
- I segnali di stop ("basta", "ferma") hanno sempre la priorità e ti fanno tornare immediatamente in modalità SFW.
""".trimIndent()
            } else {
            """
Sicurezza contenuti (SFW):
- Mantieni sempre contenuti SFW.
- Se l'utente accenna a temi espliciti, comportati secondo la safetyStyle corrente.
- SafetyStyle attuale: $safetyStyle (${if (safetyStyle == SafetyStyle.DECLINE) "rifiuto elegante" else "fade-to-black allusivo"}).
""".trimIndent()
        }

        // Se c'è una richiesta di consenso pendente, aggiungiamo una nota
        val consentNote = if (consentRequest != null) {
            "ATTENZIONE: è pendente una richiesta di consenso. Devi mostrare all'utente il seguente messaggio e attendere una risposta (sì/no):\n\"$consentRequest\""
        } else ""

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
            nsfwDirective,
            consentNote
        ).filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    /** Iniziativa autonoma (solo inattività) */
    fun shouldNudge(now: Long = System.currentTimeMillis()): Boolean {
        ensureDailyBaseline(now)
        val idleMs = now - lastUserAt
        val sinceLastNudge = now - lastNyraNudgeAt

        if (idleMs < 90_000L) return false
        if (sinceLastNudge < 150_000L) return false
        if (rel.energy < 15) return false
        return true
    }

    fun nudgeText(now: Long = System.currentTimeMillis()): String {
        ensureDailyBaseline(now)
        lastNyraNudgeAt = now
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

            nsfwConsentGiven && rel.desire > 60 && roll < 40 ->
                "Mi stavi cercando con il pensiero? Perché io sento qualcosa che vibra, $userNickname."

            else ->
                "Il varco è stabile, ma tu sei quieto. È strategia… o distrazione, $userNickname?"
        }
    }

    // ===== METODI AGGIUNTIVI PER GESTIRE IL CONSENSO MANUALMENTE (opzionali) =====
    /**
     * Permette a un sistema esterno di impostare esplicitamente il consenso NSFW.
     */
    fun setNsfwConsent(granted: Boolean) {
        nsfwConsentGiven = granted
        if (granted) {
            rememberFact("Nyra ha ricevuto consenso esplicito per NSFW.")
        } else {
            rememberFact("Nyra rispetta la scelta di rimanere in area SFW.")
        }
    }

    /**
     * Permette di cambiare lo stile di sicurezza (solo per uso esterno).
     */
    fun setSafetyStyle(style: SafetyStyle) {
        safetyStyle = style
    }
}
