package nz.cassey.bptracker

/**
 * CSV shape:
 *
 *     date,time,state,systolic,diastolic,pulse,rating
 *     2026-08-29,08:35,exercise,130,79,87,-
 *
 * Dates are ISO and state/rating are plain words, not emoji — emoji in CSV
 * mangles depending on what opens the file, and the rating is re-derived on
 * import anyway so the numbers stay the single source of truth.
 */
object Csv {

    const val HEADER = "date,time,state,systolic,diastolic,pulse,rating"

    fun export(rows: List<Reading>): String {
        val sb = StringBuilder(HEADER).append('\n')
        // Oldest first in the file reads more naturally than the screen order.
        for (r in rows.sortedWith(compareBy({ it.date }, { it.time }))) {
            sb.append(r.date).append(',')
                .append(r.time).append(',')
                .append(r.state).append(',')
                .append(r.sys).append(',')
                .append(r.dia).append(',')
                .append(r.pulse).append(',')
                .append(r.rating).append('\n')
        }
        return sb.toString()
    }

    class Result(val rows: List<Reading>, val skipped: Int)

    fun parse(text: String): Result {
        val rows = ArrayList<Reading>()
        var skipped = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            if (line.startsWith("date,", ignoreCase = true)) continue

            val f = line.split(',')
            if (f.size < 6) { skipped++; continue }

            val date = normaliseDate(f[0].trim())
            val time = normaliseTime(f[1].trim())
            val state = if (f[2].trim().lowercase() == State.EXERCISE) State.EXERCISE else State.RESTING
            val sys = f[3].trim().toIntOrNull()
            val dia = f[4].trim().toIntOrNull()
            val pulse = f[5].trim().toIntOrNull()

            if (date == null || time == null || sys == null || dia == null || pulse == null) {
                skipped++; continue
            }

            rows.add(
                Reading(
                    date = date, time = time, state = state,
                    sys = sys, dia = dia, pulse = pulse,
                    rating = Rating.of(state, sys, dia)
                )
            )
        }
        return Result(rows, skipped)
    }

    /** Accepts yyyy-MM-dd, and also dd/MM/yyyy in case a file was hand-edited. */
    private fun normaliseDate(s: String): String? {
        Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(s)?.let { return s }
        Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""").find(s)?.let { m ->
            val (d, mo, y) = m.destructured
            return "%s-%02d-%02d".format(y, mo.toInt(), d.toInt())
        }
        return null
    }

    private fun normaliseTime(s: String): String? {
        val m = Regex("""^(\d{1,2}):(\d{2})""").find(s) ?: return null
        val (h, mi) = m.destructured
        val hh = h.toInt()
        if (hh > 23 || mi.toInt() > 59) return null
        return "%02d:%s".format(hh, mi)
    }
}
