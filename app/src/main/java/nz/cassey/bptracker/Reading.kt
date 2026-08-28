package nz.cassey.bptracker

/**
 * One blood-pressure measurement.
 *
 * [date] is always stored ISO (yyyy-MM-dd) and [time] as HH:mm, 24-hour.
 * Display formatting to dd/MM/yyyy happens only in the UI layer, so that
 * CSV round-trips stay unambiguous.
 */
data class Reading(
    val id: Long = 0L,
    val date: String,
    val time: String,
    val state: String,
    val sys: Int,
    val dia: Int,
    val pulse: Int,
    val rating: String,
    val photo: String? = null
)

object State {
    const val RESTING = "resting"
    const val EXERCISE = "exercise"

    fun emoji(s: String) = if (s == EXERCISE) "\uD83D\uDEB4" else "\uD83D\uDE34"
    fun label(s: String) = if (s == EXERCISE) "Exercise" else "Resting"
}

/**
 * Rating bands follow the European / NZ classification. The worse of the two
 * numbers decides the band, e.g. 130/79 rates as WATCH on the systolic alone.
 *
 * Readings taken after exercise are deliberately left unrated — the thresholds
 * below only mean anything for a resting measurement.
 */
object Rating {
    const val GOOD = "good"
    const val OK = "ok"
    const val WATCH = "watch"
    const val HIGH = "high"
    const val NONE = "-"

    fun of(state: String, sys: Int, dia: Int): String {
        if (state == State.EXERCISE) return NONE
        return when {
            sys >= 140 || dia >= 90 -> HIGH
            sys >= 130 || dia >= 85 -> WATCH
            sys >= 120 || dia >= 80 -> OK
            else -> GOOD
        }
    }

    fun emoji(r: String) = when (r) {
        GOOD -> "\uD83D\uDC4D"
        OK -> "\uD83C\uDD97"
        WATCH -> "\u26A0\uFE0F"
        HIGH -> "\uD83D\uDE1E"
        else -> "\u2013"
    }

    fun label(r: String) = when (r) {
        GOOD -> "Good"
        OK -> "OK"
        WATCH -> "Watch"
        HIGH -> "High"
        else -> "Not rated"
    }
}
