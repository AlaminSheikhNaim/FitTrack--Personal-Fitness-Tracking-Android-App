package com.example.fittrack

// One reading taken during a short sampling window (about 30 seconds, every ~10 minutes).
data class SleepSample(
    val tsMs: Long,
    val lux: Float,          // light level in lux
    val covered: Boolean,    // proximity sensor says the phone is covered (pillow, blanket, face down)
    val noiseDb: Float,      // typical (steady) sound level during the window, for reference
    val noiseEvents: Int,    // short sounds that stood out ABOVE the steady background (fan and wind do not count)
    val movement: Float      // phone movement during the window (std-dev of acceleration, m/s^2)
)

data class SleepQualityResult(
    val score: Int,          // 0..100
    val label: String,       // Excellent / Good / Fair / Poor
    val windows: Int,
    val restlessWindows: Int,
    val noisyWindows: Int,
    val brightWindows: Int
)

object SleepScorer {
    // Tuning values. If the score feels too harsh or too easy after a few nights, change these.
    const val MOVEMENT_THRESHOLD = 0.05f      // above this, the window counts as restless
    const val NOISE_EVENTS_THRESHOLD = 2      // this many standout sounds make a window noisy
    const val LIGHT_RATIO = 3f                // brighter than 3x this night's normal darkness
    const val LIGHT_MIN_STEP = 1f             // ...and at least this many lux above it

    fun score(samples: List<SleepSample>): SleepQualityResult? {
        if (samples.isEmpty()) return null

        // "Normal darkness" is learned from THIS night: the 20th percentile of the light readings.
        // So a room with a little street light and a pitch-black room are both judged fairly.
        val lights = samples.filter { !it.covered }.map { it.lux }.sorted()
        val darkLevel = if (lights.isEmpty()) 0f else lights[(lights.size * 20) / 100]
        val brightLimit = darkLevel * LIGHT_RATIO + LIGHT_MIN_STEP

        var restless = 0
        var noisy = 0
        var bright = 0
        for (s in samples) {
            if (s.movement > MOVEMENT_THRESHOLD) restless++
            if (s.noiseEvents >= NOISE_EVENTS_THRESHOLD) noisy++
            if (!s.covered && s.lux > brightLimit) bright++   // covered phone: light reading is ignored
        }

        val n = samples.size.toFloat()
        val penalty = 45f * restless / n + 30f * noisy / n + 25f * bright / n
        val score = (100f - penalty).toInt().coerceIn(0, 100)
        val label = when {
            score >= 85 -> "Excellent"
            score >= 70 -> "Good"
            score >= 50 -> "Fair"
            else -> "Poor"
        }
        return SleepQualityResult(score, label, samples.size, restless, noisy, bright)
    }
}