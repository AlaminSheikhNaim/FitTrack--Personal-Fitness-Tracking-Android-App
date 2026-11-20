package com.example.fittrack

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

// Turns the raw readings of one monitored night into a sleep quality report.
// All the limits below are starting values that can be tuned to your room.
object SleepAnalyzer {

    private class Factor(val phrase: String, val tip: String, val loss: Double)

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun clamp(v: Double): Int = v.roundToInt().coerceIn(0, 100)

    private fun num(v: Double): String = cleanNum(v.toFloat())

    private fun markRange(flags: BooleanArray, startMin: Long, fromMs: Long, toMs: Long) {
        val from = (fromMs / 60_000L - startMin).toInt().coerceIn(0, flags.size - 1)
        val to = (toMs / 60_000L - startMin).toInt().coerceIn(0, flags.size - 1)
        for (i in from..to) flags[i] = true
    }

    private fun lightWord(lux: Double): String = when {
        lux >= 50.0 -> "Bright light"
        lux >= 10.0 -> "Moderate light"
        else -> "Mild light"
    }

    fun failed(session: SessionInfo): QualityReport = empty(
        session, "This session could not be analysed."
    )

    private fun empty(session: SessionInfo, summary: String): QualityReport {
        val zone = ZoneId.systemDefault()
        val durationMin = ((session.endMs - session.startMs) / 60_000L).toInt().coerceAtLeast(0)
        return QualityReport(
            sessionId = session.id,
            day = Instant.ofEpochMilli(session.endMs).atZone(zone).toLocalDate().toString(),
            startMs = session.startMs,
            endMs = session.endMs,
            score = -1,
            movementScore = -1,
            soundScore = -1,
            lightScore = -1,
            interruptionScore = -1,
            durationMin = durationMin,
            asleepMin = 0,
            latencyMin = 0,
            interruptions = 0,
            restlessMoves = 0,
            soundEvents = 0,
            lightEvents = 0,
            test = session.test,
            summary = summary,
            tips = "",
            disturbances = emptyList()
        )
    }

    fun analyze(
        session: SessionInfo,
        blocks: List<MonitorBlock>,
        motion: List<MinuteMotion>,
        events: List<ScreenEvent>
    ): QualityReport {
        val zone = ZoneId.systemDefault()
        val durationMin = ((session.endMs - session.startMs) / 60_000L).toInt().coerceAtLeast(0)
        if (durationMin < 3 || blocks.isEmpty()) {
            return empty(
                session,
                "This session was too short to rate. Keep monitoring for at least a few minutes."
            )
        }
        val day = Instant.ofEpochMilli(session.endMs).atZone(zone).toLocalDate().toString()

        // ---- One slot per minute: was the phone used, or was there movement? ----
        val startMin = session.startMs / 60_000L
        val slots = durationMin + 1
        val awake = BooleanArray(slots)

        // The screen is on when monitoring starts, until the first screen-off event
        var screenOnSince = session.startMs
        for (e in events) {
            if (e.kind == "SCREEN_ON") {
                if (screenOnSince < 0) screenOnSince = e.tsMs
            } else if (e.kind == "SCREEN_OFF") {
                if (screenOnSince >= 0) {
                    markRange(awake, startMin, screenOnSince, e.tsMs)
                    screenOnSince = -1L
                }
            }
        }
        if (screenOnSince >= 0) markRange(awake, startMin, screenOnSince, session.endMs)

        // ---- Movement: minutes where the strongest jolt is well above the usual ----
        val motionAvailable = motion.size >= 5
        val moveLimit = maxOf(0.15, 4.0 * median(motion.map { it.max }))
        val restless = BooleanArray(slots)
        var moves = 0
        var lastFlag = -10
        for (m in motion) {
            val idx = (m.minuteMs / 60_000L - startMin).toInt()
            if (idx < 0 || idx >= slots) continue
            if (m.max > moveLimit) {
                restless[idx] = true
                awake[idx] = true
                if (idx - lastFlag > 1) moves++
                lastFlag = idx
            }
        }

        // ---- When did you fall asleep? ----
        // A minute counts as "up" if the screen was on, the phone moved, the room light was
        // clearly brighter than usual, or there was sustained sound (talking, TV).
        val sustainedAt = maxOf(3, session.windowS / 4)
        val up = awake.copyOf()
        val litBlocks = blocks.filter { !it.covered && it.lightLux != null }
        val roomLight = if (litBlocks.isEmpty()) 0.0 else median(litBlocks.map { it.lightLux ?: 0.0 })
        val brightAt = maxOf(roomLight * 3.0, roomLight + 1.5)
        val readingMs = maxOf(session.windowS, 30) * 1000L
        for (b in blocks) {
            val bright = !b.covered && (b.lightLux ?: 0.0) > brightAt
            val talking = b.noiseEvents >= sustainedAt
            if (bright || talking) markRange(up, startMin, b.tsMs - readingMs, b.tsMs)
        }

        var onset = 0
        if (durationMin >= 20) {
            // Longer nights need a longer calm stretch (15 min, one twitch allowed), and it must
            // hold: real sleep is followed by mostly calm minutes, not by picking the phone up again.
            val calm = if (durationMin >= 45) 15 else 10
            val confirm = if (durationMin >= 90) 45 else 0
            fun upCount(from: Int, len: Int): Int {
                var n = 0
                for (k in from until minOf(from + len, slots)) if (up[k]) n++
                return n
            }
            var found = -1
            for (i in 0..(slots - calm)) {
                if (up[i]) continue
                if (upCount(i, calm) > 1) continue
                if (confirm > 0 && upCount(i, confirm) > confirm / 5) continue
                found = i
                break
            }
            if (found < 0) {
                // Fallback: the first 10 calm minutes in a row
                for (i in 0..(slots - 11)) {
                    if ((0 until 10).none { up[i + it] }) {
                        found = i
                        break
                    }
                }
            }
            if (found > 0) onset = found
        }
        val onsetMs = session.startMs + onset * 60_000L
        val asleepMin = (onset until durationMin).count { !awake[it] }
        val span = (durationMin - onset).coerceAtLeast(1)

        // ---- Phone pick-ups after you fell asleep (not the final one when you wake up) ----
        val pickups = events.filter {
            it.kind == "SCREEN_ON" &&
                    it.tsMs > onsetMs + 5 * 60_000L &&
                    it.tsMs < session.endMs - 3 * 60_000L
        }

        var night = blocks.filter { it.tsMs >= onsetMs }
        if (night.size < 2) night = blocks
        // Ignore the last 10 minutes of a long night: morning light and noise are normal
        val endCut = if (durationMin >= 60) session.endMs - 10 * 60_000L else session.endMs + 1

        // ---- Light: compared with your room's own usual dark level ----
        val lightBlocks = night.filter { !it.covered && it.lightLux != null && it.tsMs < endCut }
        val lightAvailable = lightBlocks.size >= 3
        val lightBase = if (lightBlocks.isEmpty()) 0.0 else median(lightBlocks.map { it.lightLux ?: 0.0 })
        val lightLimit = maxOf(lightBase * 3.0, lightBase + 1.5)
        val lightFlags = lightBlocks.filter { (it.lightLux ?: 0.0) > lightLimit }
        val lightFrac = if (lightBlocks.isEmpty()) 0.0 else lightFlags.size.toDouble() / lightBlocks.size

        // ---- Sound: compared with your room's usual background level (fan, air conditioner) ----
        val soundBlocks = night.filter {
            it.noiseFloorDb != null && it.noisePeakDb != null && it.tsMs < endCut
        }
        val soundAvailable = soundBlocks.size >= 3
        val soundBase = if (soundBlocks.isEmpty()) 0.0 else median(soundBlocks.map { it.noiseFloorDb ?: 0.0 })
        val soundFlags = soundBlocks.filter {
            it.noiseEvents >= 2 || ((it.noisePeakDb ?: 0.0) - soundBase) >= 25.0
        }
        val soundFrac = if (soundBlocks.isEmpty()) 0.0 else soundFlags.size.toDouble() / soundBlocks.size

        // ---- Scores ----
        val restlessInSpan = (onset until durationMin).count { restless[it] }
        val movementScore = if (motionAvailable) clamp(100.0 - 450.0 * restlessInSpan / span) else -1
        val soundScore = if (soundAvailable) clamp(100.0 - 150.0 * soundFrac) else -1
        val lightScore = if (lightAvailable) clamp(100.0 - 150.0 * lightFrac) else -1
        val interruptionScore = clamp(100.0 - 25.0 * pickups.size)

        var weighted = 0.0
        var weights = 0.0
        fun add(score: Int, weight: Double) {
            if (score >= 0) {
                weighted += score * weight
                weights += weight
            }
        }
        add(movementScore, 35.0)
        add(soundScore, 25.0)
        add(lightScore, 20.0)
        add(interruptionScore, 20.0)
        val score = if (weights > 0.0) (weighted / weights).roundToInt() else -1

        // ---- The list of what disturbed sleep, with times ----
        val list = ArrayList<Disturbance>()
        for (b in lightFlags) {
            val lux = b.lightLux ?: 0.0
            list.add(
                Disturbance(
                    b.tsMs, "LIGHT",
                    "${lightWord(lux)}: ${num(lux)} lx (your room is usually ${num(lightBase)} lx)"
                )
            )
        }
        for (b in soundFlags) {
            val delta = (b.noisePeakDb ?: 0.0) - soundBase
            val text = if (b.noiseEvents >= sustainedAt) {
                "Sustained sound for about ${b.noiseEvents} s (talking, TV or snoring?), " +
                        "up to ${num(delta)} dB above the room's usual level"
            } else {
                val size = if (delta >= 30.0) "Very loud sudden sound" else if (delta >= 20.0) "Loud sudden sound" else "Sudden sound"
                "$size, ${num(delta)} dB above the room's usual level"
            }
            list.add(Disturbance(b.tsMs, "SOUND", text))
        }
        var i = onset
        while (i < durationMin) {
            if (restless[i]) {
                var j = i
                while (j + 1 < durationMin && restless[j + 1]) j++
                val len = j - i + 1
                list.add(
                    Disturbance(
                        session.startMs + i * 60_000L, "MOVEMENT",
                        if (len == 1) "Restless movement" else "Restless for $len min"
                    )
                )
                i = j + 1
            } else {
                i++
            }
        }
        for (p in pickups) {
            list.add(Disturbance(p.tsMs, "PHONE", "Phone picked up and the screen turned on"))
        }
        val sorted = list.sortedBy { it.tsMs }.take(60)

        // ---- Summary and tips ----
        val factors = ArrayList<Factor>()
        if (lightScore in 0..79 && lightFlags.isNotEmpty()) {
            val maxLux = lightFlags.maxOf { it.lightLux ?: 0.0 }
            val phrase = when {
                maxLux >= 50.0 -> "too much light (up to ${num(maxLux)} lx)"
                maxLux >= 10.0 -> "moderate light (up to ${num(maxLux)} lx)"
                else -> "mild light (up to ${num(maxLux)} lx)"
            }
            factors.add(
                Factor(
                    phrase,
                    "Close any curtain gap, cover standby lights, or try a sleep mask.",
                    20.0 * (100 - lightScore) / 100.0
                )
            )
        }
        if (soundScore in 0..79 && soundFlags.isNotEmpty()) {
            val sustained = soundFlags.any { it.noiseEvents >= sustainedAt }
            val maxDelta = soundFlags.maxOf { (it.noisePeakDb ?: 0.0) - soundBase }
            val phrase = when {
                sustained -> "sustained noise such as talking, TV or snoring"
                maxDelta >= 30.0 -> "huge sudden noise"
                else -> "sudden sounds"
            }
            factors.add(
                Factor(
                    phrase,
                    "Try earplugs, or move noise sources like a TV or phone away from the bed.",
                    25.0 * (100 - soundScore) / 100.0
                )
            )
        }
        if (movementScore in 0..79) {
            factors.add(
                Factor(
                    "restless movement ($moves ${if (moves == 1) "time" else "times"})",
                    "Keep the room cool, avoid caffeine late in the day and keep a regular bedtime.",
                    35.0 * (100 - movementScore) / 100.0
                )
            )
        }
        if (interruptionScore in 0..79) {
            factors.add(
                Factor(
                    "using the phone (${pickups.size} ${if (pickups.size == 1) "time" else "times"})",
                    "Avoid checking the phone at night and use Do Not Disturb.",
                    20.0 * (100 - interruptionScore) / 100.0
                )
            )
        }
        factors.sortByDescending { it.loss }

        val prefix = if (durationMin < 30) "Short session, so this is only a preview. " else ""
        val summary = when {
            score < 0 -> "Not enough data to rate this night."
            factors.isEmpty() ->
                prefix + "Your sleep was calm, with no significant disturbance from light, sound or movement."
            else -> {
                val also = if (factors.size > 1) {
                    " Also: " + factors.drop(1).joinToString(", ") { it.phrase } + "."
                } else {
                    ""
                }
                prefix + "Your sleep was mainly disturbed by ${factors[0].phrase}.$also"
            }
        }

        val notes = ArrayList<String>()
        factors.take(3).forEach { notes.add(it.tip) }
        if (!motionAvailable) notes.add("Movement was not measured (too little data). Keep the phone on the bed.")
        if (!soundAvailable) notes.add("Sound was not measured (microphone unavailable or too few readings).")
        if (!lightAvailable) notes.add("Light was not measured (the phone was covered or there were too few readings).")

        return QualityReport(
            sessionId = session.id,
            day = day,
            startMs = session.startMs,
            endMs = session.endMs,
            score = score,
            movementScore = movementScore,
            soundScore = soundScore,
            lightScore = lightScore,
            interruptionScore = interruptionScore,
            durationMin = durationMin,
            asleepMin = asleepMin,
            latencyMin = onset,
            interruptions = pickups.size,
            restlessMoves = moves,
            soundEvents = soundFlags.size,
            lightEvents = lightFlags.size,
            test = session.test,
            summary = summary,
            tips = notes.joinToString("\n"),
            disturbances = sorted
        )
    }
}