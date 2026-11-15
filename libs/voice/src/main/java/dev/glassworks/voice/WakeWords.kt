package dev.glassworks.voice

/**
 * An official microWakeWord v2 model from esphome/micro-wake-word-models (Apache-2.0), with the
 * values of its manifest (`models/v2/<id>.json`) at [WakeWords.MODELS_COMMIT].
 *
 * @property id      The wake word id Home Assistant stores (`hey_jarvis`).
 * @property phrase  The phrase, as shown and sent with a detection.
 * @property sha256  The model file's SHA-256; a fetched file must match it.
 * @property cutoff  `probability_cutoff`: the default threshold for the sliding-window mean.
 * @property window  `sliding_window_size`: how many model outputs are averaged.
 */
public data class MicroModel(
    public val id: String,
    public val phrase: String,
    public val sha256: String,
    public val cutoff: Float,
    public val window: Int,
)

/** A wake word Home Assistant can pick. Its [id] is what the wake word select stores. */
public sealed interface WakeWord {
    /** What Home Assistant's wake word select stores. */
    public val id: String

    /** The phrase sent to Home Assistant with a detection. */
    public val phrase: String

    /** A microWakeWord word, from one of the official [model]s. */
    public data class Micro(
        /** Its model. */
        public val model: MicroModel,
    ) : WakeWord {
        override val id: String get() = model.id
        override val phrase: String get() = model.phrase
    }

    /**
     * An openWakeWord model, `<id>.tflite` in the models directory. Kept for one release as a
     * fallback (#175); its models are CC BY-NC-SA 4.0.
     */
    public data class Legacy(override val id: String) : WakeWord {
        override val phrase: String
            get() = id.replace(Regex("_v\\d+(\\.\\d+)*$"), "").split('_').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }
}

/** The wake words the library knows: the official microWakeWord models, and how stored ids read. */
public object WakeWords {
    /** esphome/micro-wake-word-models commit the models and their manifest values come from. */
    public const val MODELS_COMMIT: String = "40ff33f57f8fc6ad71a75ef085abbf742495b225"

    /** The official microWakeWord v2 models, fetched on first use. */
    public val MICRO: List<MicroModel> = listOf(
        MicroModel("okay_nabu", "Okay Nabu", "0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891", 0.97f, 5),
        MicroModel("hey_jarvis", "Hey Jarvis", "21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77", 0.97f, 5),
        MicroModel("alexa", "Alexa", "9011a8155b04de858c48038529235cbc0e42e9fca05a55bf588cb80a653a723b", 0.9f, 5),
        MicroModel("hey_mycroft", "Hey Mycroft", "c2a9b6ed51182db72e014781d5a4ece1929dc232a40b5b4be384f0295f0e1571", 0.95f, 5),
    )

    /** The word a new panel listens for, the same as before microWakeWord. */
    public const val DEFAULT: String = "hey_jarvis"

    /** The wake word for a stored [id]; null for "" (no wake word). */
    public fun parse(id: String): WakeWord? {
        if (id.isEmpty()) return null
        return MICRO.firstOrNull { it.id == id }?.let { WakeWord.Micro(it) } ?: WakeWord.Legacy(id)
    }

    /**
     * The wake word id for a value of the old `wake_model` setting, an openWakeWord file name such
     * as `hey_jarvis_v0.1.tflite`. The words microWakeWord also has move to it (`hey_jarvis`);
     * another file stays an openWakeWord word (`my_word_v2.tflite` -> `my_word_v2`). A value without
     * `.tflite` is already an id and is kept.
     */
    public fun migrate(value: String): String {
        val v = value.trim()
        if (!v.endsWith(".tflite")) return v
        val legacy = v.removeSuffix(".tflite")
        val word = legacy.replace(Regex("_v\\d+(\\.\\d+)*$"), "")
        return if (MICRO.any { it.id == word }) word else legacy
    }
}
