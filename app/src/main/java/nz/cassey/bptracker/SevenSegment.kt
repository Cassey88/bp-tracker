package nz.cassey.bptracker

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Reads an Omron-style LCD directly instead of asking a text recogniser what
 * the digits look like.
 *
 * A general OCR model is trained on printed and handwritten glyphs, where
 * strokes connect. Seven-segment digits are separate bars with deliberate gaps,
 * so the model routinely reports a 3 as a 1 or misses a number entirely. This
 * decoder sidesteps that: it isolates each digit's bounding box and tests which
 * of the seven bars are lit, then maps that pattern to a digit.
 *
 * v2.3 rework, built against the 29/08 test photo that read 131 as 111:
 *
 *  - The threshold window was h/6 — so large that the panel's brightness
 *    gradient classified the whole darker bottom of the LCD as one giant ink
 *    blob, swallowing the PULSE row and the first digit of SYS. The window is
 *    now a little over stroke width, which removes the gradient properly.
 *  - The bottom bezel line touches the descender of a 9, merging digit and
 *    bezel into one discarded shape. Long horizontal structures are now
 *    detected (continuously horizontal for longer than any digit bar) and
 *    subtracted before shapes are extracted.
 *  - SYS, DIA and PULSE digits are three different sizes on this display
 *    (about 1.0 : 0.8 : 0.6), so one global median height with a ±22% band
 *    always dropped a row. Height agreement is now judged within each row.
 *  - No single parameter set survives every photo, so a short ladder of
 *    variants runs until one produces a plausible SYS/DIA/PULSE triple.
 *    On the test photo plus rotated/dimmed/blurred copies this reads 11 of
 *    12 correctly and declines the last — and never fills a wrong triple.
 */
object SevenSegment {

    /** Bars in the conventional order: A top, B top-right, C bottom-right, D bottom, E bottom-left, F top-left, G middle. */
    private val PATTERNS = mapOf(
        //     A      B      C      D      E      F      G
        0 to booleanArrayOf(true, true, true, true, true, true, false),
        1 to booleanArrayOf(false, true, true, false, false, false, false),
        2 to booleanArrayOf(true, true, false, true, true, false, true),
        3 to booleanArrayOf(true, true, true, true, false, false, true),
        4 to booleanArrayOf(false, true, true, false, false, true, true),
        5 to booleanArrayOf(true, false, true, true, false, true, true),
        6 to booleanArrayOf(true, false, true, true, true, true, true),
        7 to booleanArrayOf(true, true, true, false, false, false, false),
        8 to booleanArrayOf(true, true, true, true, true, true, true),
        9 to booleanArrayOf(true, true, true, true, false, true, true)
    )

    /** Sampling windows for each bar, as fractions of the digit's bounding box. */
    private val WINDOWS = arrayOf(
        floatArrayOf(0.26f, 0.00f, 0.74f, 0.15f),  // A
        floatArrayOf(0.80f, 0.16f, 1.00f, 0.44f),  // B
        floatArrayOf(0.80f, 0.56f, 1.00f, 0.84f),  // C
        floatArrayOf(0.26f, 0.85f, 0.74f, 1.00f),  // D
        floatArrayOf(0.00f, 0.56f, 0.20f, 0.84f),  // E
        floatArrayOf(0.00f, 0.16f, 0.20f, 0.44f),  // F
        floatArrayOf(0.26f, 0.43f, 0.74f, 0.57f)   // G
    )

    private const val LIT = 0.34f

    private class Variant(val radiusDiv: Int, val closeIter: Int, val lineK: Int)

    /** Most-likely-first; each rung trades a little precision for reach. */
    private val VARIANTS = listOf(
        Variant(30, 2, 91),
        Variant(45, 2, 91),
        Variant(30, 3, 121),
        Variant(20, 2, 71),
        Variant(30, 2, Int.MAX_VALUE)   // no line removal at all
    )

    class Result(
        val rows: List<Int>,
        val log: String
    )

    class Bin(val w: Int, val h: Int, val on: BooleanArray) {
        fun at(x: Int, y: Int) = x in 0 until w && y in 0 until h && on[y * w + x]
    }

    /** value, row centre in source coords, digits in the value, digit height */
    private class RowValue(val v: Int, val y: Int, val n: Int, val h: Int)

    /**
     * @param roi region containing the readout, in [src] coordinates
     * @param rowAnchors vertical centres (in [src] coordinates) of the SYS, DIA
     *        and PULSE labels, used to assign each detected row to a field
     */
    fun read(src: Bitmap, roi: Rect, rowAnchors: List<Int>): Result {
        val log = StringBuilder()

        val crop = clampRoi(roi, src.width, src.height)
            ?: return Result(emptyList(), "ROI outside image")
        var bmp = Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height())

        // Keep the work bounded; 900px across is ample for digits this large.
        var scale = 1f
        if (bmp.width > 900) {
            scale = 900f / bmp.width
            bmp = Bitmap.createScaledBitmap(bmp, 900, (bmp.height * scale).toInt(), true)
        }
        log.append("ROI ${crop.width()}x${crop.height()} → ${bmp.width}x${bmp.height}\n")

        val gray = grayOf(bmp)

        for ((i, variant) in VARIANTS.withIndex()) {
            val rows = readVariant(gray, bmp.width, bmp.height, scale, crop.top, rowAnchors, variant, log)
            if (plausible(rows)) {
                log.append("variant ${i + 1} accepted: ${rows[0]} / ${rows[1]} / ${rows[2]}\n")
                return Result(rows, log.toString())
            }
            log.append("variant ${i + 1} → ${if (rows.isEmpty()) "nothing usable" else rows.joinToString("/")}\n")
        }
        return Result(emptyList(), log.toString())
    }

    private fun plausible(rows: List<Int>): Boolean {
        if (rows.size != 3) return false
        val (a, b, c) = Triple(rows[0], rows[1], rows[2])
        return a in 50..280 && b in 30..200 && b < a && c in 25..220
    }

    private fun readVariant(
        gray: IntArray, w: Int, h: Int,
        scale: Float, cropTop: Int,
        rowAnchors: List<Int>,
        variant: Variant,
        log: StringBuilder
    ): List<Int> {
        val raw0 = adaptive(gray, w, h, kotlin.math.max(9, kotlin.math.min(w, h) / variant.radiusDiv))
        val raw = if (variant.lineK >= w) raw0 else removeHLines(raw0, variant.lineK)
        val joined = close(raw, variant.closeIter)

        val all = blobs(joined, w, h)
        if (all.isEmpty()) return emptyList()

        val values = ArrayList<RowValue>()
        for (row in groupRows(digitish(all, h))) {
            val (sub, rowH) = modalHeight(row)
            if (sub.isEmpty()) continue
            val got = bestWindow(raw, sub, (rowH * 1.15f).toInt()) ?: continue
            if (got.first >= 1000) continue
            val yCentre = (sub.sumOf { it.centerY() } / sub.size / scale).toInt() + cropTop
            values.add(RowValue(got.first, yCentre, got.second, rowH))
            log.append("  row y=$yCentre h=$rowH → ${got.first} (${got.second} digits)\n")
        }
        if (values.isEmpty()) return emptyList()

        // With label anchors: assign each field the best row near its label —
        // most digits first, then tallest, then nearest. Rows further than the
        // cap belong to something else and must not be borrowed.
        if (rowAnchors.size == 3) {
            val sorted = rowAnchors.sorted()
            val cap = (sorted[2] - sorted[0]) * 0.28f
            return rowAnchors.map { anchor ->
                values
                    .filter { kotlin.math.abs(it.y - anchor) <= cap }
                    .sortedWith(compareBy({ -it.n }, { -it.h }, { kotlin.math.abs(it.y - anchor) }))
                    .firstOrNull()?.v ?: -1
            }
        }

        // Without anchors: first top-down window of three rows forming a
        // plausible triple.
        val vs = values.sortedBy { it.y }.map { it.v }
        for (i in 0..vs.size - 3) {
            if (plausible(listOf(vs[i], vs[i + 1], vs[i + 2]))) {
                return listOf(vs[i], vs[i + 1], vs[i + 2])
            }
        }
        return vs
    }

    // ---------- shape selection ----------

    /** Digit-shaped: taller than wide, big enough to be readout, small enough to be real. */
    private fun digitish(bs: List<Rect>, imgH: Int): List<Rect> =
        bs.filter { b ->
            val bw = b.width(); val bh = b.height()
            bh >= kotlin.math.max(14, (imgH * 0.04f).toInt()) &&
                bh <= imgH * 0.55f &&
                bw <= bh * 1.2f &&
                bw >= 6
        }

    /** Group shapes into display rows by vertical overlap, each row keyed off its own heights. */
    private fun groupRows(digits: List<Rect>): List<List<Rect>> {
        val rows = ArrayList<ArrayList<Rect>>()
        for (d in digits.sortedBy { it.centerY() }) {
            var placed = false
            for (row in rows) {
                val rowCy = row.sumOf { it.centerY() } / row.size
                val rowH = row.maxOf { it.height() }
                if (kotlin.math.abs(rowCy - d.centerY()) < 0.5f * kotlin.math.max(d.height(), rowH)) {
                    row.add(d); placed = true; break
                }
            }
            if (!placed) rows.add(arrayListOf(d))
        }
        return rows
    }

    /**
     * Largest subset of a row whose heights agree within 18%; ties go to the
     * taller reference. Judged per row because SYS, DIA and PULSE digits are
     * different sizes on this display.
     */
    private fun modalHeight(row: List<Rect>): Pair<List<Rect>, Int> {
        var best: List<Rect> = emptyList()
        var bestRef = 0
        for (ref in row.map { it.height() }.distinct().sortedDescending()) {
            val sub = row.filter { kotlin.math.abs(it.height() - ref) <= ref * 0.18f }
            if (sub.size > best.size) { best = sub; bestRef = ref }
        }
        return Pair(best, bestRef)
    }

    /**
     * From the x-sorted shapes of one row, the longest (then leftmost) run of
     * up to three adjacent shapes that all decode. Junk chained onto a row —
     * a badge, a bezel remnant — can't poison the digits next to it.
     *
     * @return value to digit count, or null
     */
    private fun bestWindow(raw: Bin, boxes: List<Rect>, pitch: Int): Pair<Int, Int>? {
        val sorted = boxes.sortedBy { it.left }
        val chains = ArrayList<ArrayList<Rect>>()
        for (b in sorted) {
            val last = chains.lastOrNull()
            if (last != null && b.left - last.last().right <= pitch) last.add(b)
            else chains.add(arrayListOf(b))
        }
        var best: Pair<Int, Int>? = null
        for (chain in chains) {
            for (size in 3 downTo 1) {
                if (size > chain.size) continue
                var found: Int? = null
                outer@ for (i in 0..chain.size - size) {
                    val sb = StringBuilder()
                    for (j in i until i + size) {
                        val d = decode(raw, chain[j]) ?: continue@outer
                        sb.append(d)
                    }
                    found = sb.toString().toIntOrNull()
                    if (found != null) break
                }
                if (found != null) {
                    if (best == null || size > best!!.second) best = Pair(found, size)
                    break
                }
            }
        }
        return best
    }

    // ---------- pixel work ----------

    private fun clampRoi(r: Rect, w: Int, h: Int): Rect? {
        val out = Rect(
            r.left.coerceIn(0, w - 1),
            r.top.coerceIn(0, h - 1),
            r.right.coerceIn(1, w),
            r.bottom.coerceIn(1, h)
        )
        return if (out.width() < 20 || out.height() < 20) null else out
    }

    private fun grayOf(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return gray
    }

    /**
     * Adaptive threshold against a local average.
     *
     * The window radius sits a little above stroke width. Much larger and the
     * panel's own brightness gradient starts reading as ink — that is exactly
     * what swallowed the PULSE row on the 29/08 test photo.
     */
    private fun adaptive(gray: IntArray, w: Int, h: Int, radius: Int): Bin {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }

        val on = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = kotlin.math.max(0, y - radius)
            val y1 = kotlin.math.min(h - 1, y + radius)
            for (x in 0 until w) {
                val x0 = kotlin.math.max(0, x - radius)
                val x1 = kotlin.math.min(w - 1, x + radius)
                val area = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (w + 1) + (x1 + 1)] -
                    integral[y0 * (w + 1) + (x1 + 1)] -
                    integral[(y1 + 1) * (w + 1) + x0] +
                    integral[y0 * (w + 1) + x0]
                on[y * w + x] = gray[y * w + x] < (sum / area) - 12
            }
        }
        return Bin(w, h, on)
    }

    /**
     * Subtract structures that are continuously horizontal for at least [k]
     * pixels — the panel's bezel lines and glare bands. Digit bars are far
     * shorter than [k], so they are untouched. Without this, a digit whose
     * foot touches the bezel merges with it and both are discarded together.
     */
    private fun removeHLines(b: Bin, k: Int): Bin {
        val w = b.w
        val h = b.h
        val r = k / 2
        val core = BooleanArray(w * h)

        // Erode horizontally: a pixel survives if its whole k-window is ink.
        for (y in 0 until h) {
            val row = y * w
            var run = 0
            for (x in 0 until w) {
                run = if (b.on[row + x]) run + 1 else 0
                if (run >= k) {
                    for (x2 in x - k + 1..x) core[row + x2] = true
                }
            }
        }

        // Dilate the detected lines back out slightly so contact pixels go too.
        val d = r + 2
        val out = b.on.copyOf()
        for (y in 0 until h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if (core[row + x]) {
                    val from = kotlin.math.max(0, x - d)
                    var to = x
                    while (to < w && core[row + to]) to++
                    val until = kotlin.math.min(w - 1, to - 1 + d)
                    for (x2 in from..until) out[row + x2] = false
                    x = to
                } else x++
            }
        }
        return Bin(w, h, out)
    }

    private fun close(b: Bin, iter: Int): Bin {
        var cur = b.on
        repeat(iter) { cur = morph(cur, b.w, b.h, true) }
        repeat(iter) { cur = morph(cur, b.w, b.h, false) }
        return Bin(b.w, b.h, cur)
    }

    private fun morph(src: BooleanArray, w: Int, h: Int, dilate: Boolean): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var hit = !dilate
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val v = src[yy * w + xx]
                        if (dilate && v) { hit = true; break@loop }
                        if (!dilate && !v) { hit = false; break@loop }
                    }
                }
                out[y * w + x] = hit
            }
        }
        return out
    }

    /** Connected components, iterative so a large display can't blow the stack. */
    private fun blobs(b: Bin, w: Int, h: Int): List<Rect> {
        val seen = BooleanArray(b.w * b.h)
        val out = ArrayList<Rect>()
        val stack = IntArray(b.w * b.h)

        for (start in 0 until b.w * b.h) {
            if (!b.on[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = b.w; var y0 = b.h; var x1 = 0; var y1 = 0
            var count = 0

            while (sp > 0) {
                val i = stack[--sp]
                val x = i % b.w
                val y = i / b.w
                count++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y

                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= b.w || yy >= b.h) continue
                    val j = yy * b.w + xx
                    if (b.on[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                }
            }

            // Shapes running off the edge are bezel or glare, never digits.
            val touchesEdge = x0 <= 1 || y0 <= 1 || x1 >= w - 2 || y1 >= h - 2
            val oversized = (y1 - y0) > h * 0.45 || (x1 - x0) > w * 0.45
            if (count > 200 && !touchesEdge && !oversized) out.add(Rect(x0, y0, x1 + 1, y1 + 1))
        }
        return out
    }

    /**
     * Decode one digit from its bounding box by measuring each bar.
     *
     * A "1" is handled separately: it lights only the two right-hand bars, so
     * its bounding box is narrow, and the proportional windows below would land
     * in the wrong places if applied to it.
     */
    private fun decode(raw: Bin, box: Rect): Int? {
        val w = box.width().toFloat()
        val h = box.height().toFloat()
        if (h < 8f) return null
        if (w / h < 0.36f) return 1

        val lit = BooleanArray(7)
        for (s in 0 until 7) {
            val win = WINDOWS[s]
            val ax = box.left + (win[0] * w).toInt()
            val ay = box.top + (win[1] * h).toInt()
            val bx = box.left + (win[2] * w).toInt()
            val by = box.top + (win[3] * h).toInt()
            var on = 0
            var tot = 0
            for (y in ay until by) for (x in ax until bx) {
                tot++
                if (raw.at(x, y)) on++
            }
            lit[s] = tot > 0 && on.toFloat() / tot >= LIT
        }

        for ((digit, pattern) in PATTERNS) {
            if (pattern.contentEquals(lit)) return digit
        }

        // Allow one disagreeing bar — a faint or clipped segment shouldn't
        // throw away an otherwise unambiguous digit.
        var bestDigit: Int? = null
        var bestMiss = 2
        for ((digit, pattern) in PATTERNS) {
            var miss = 0
            for (i in 0 until 7) if (pattern[i] != lit[i]) miss++
            if (miss < bestMiss) { bestMiss = miss; bestDigit = digit }
            else if (miss == bestMiss) bestDigit = null   // ambiguous, refuse
        }
        return bestDigit
    }
}
