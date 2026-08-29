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
 * of the seven bars are lit, then maps that pattern to a digit. The answer is
 * determined by the pixels rather than inferred, so it either reads a digit
 * correctly or reports that it couldn't.
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

    class Result(
        val rows: List<Int>,
        val log: String
    )

    class Bin(val w: Int, val h: Int, val on: BooleanArray) {
        fun at(x: Int, y: Int) = x in 0 until w && y in 0 until h && on[y * w + x]
    }

    /**
     * @param roi region containing the readout, in [src] coordinates
     * @param rowAnchors vertical centres (in [src] coordinates) of the SYS, DIA
     *        and PULSE labels, used to assign each detected row to a field
     */
    fun read(src: Bitmap, roi: Rect, rowAnchors: List<Int>): Result {
        val log = StringBuilder()

        val crop = clampRoi(roi, src.width, src.height) ?: return Result(emptyList(), "ROI outside image")
        var bmp = Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height())

        // Keep the work bounded; 900px across is ample for digits this large.
        var scale = 1f
        if (bmp.width > 900) {
            scale = 900f / bmp.width
            bmp = Bitmap.createScaledBitmap(bmp, 900, (bmp.height * scale).toInt(), true)
        }

        val raw = adaptive(bmp)
        log.append("ROI ${crop.width()}x${crop.height()} → ${bmp.width}x${bmp.height}\n")

        // Segments within one digit are separate bars. Closing joins them into
        // a single shape so the digit can be found as one connected blob, while
        // the untouched `raw` image is what the bars are actually measured on.
        val joined = close(raw, kotlin.math.max(1, bmp.height / 150))

        val all = blobs(joined, bmp.width, bmp.height)
        if (all.isEmpty()) return Result(emptyList(), log.append("no shapes found").toString())

        // Digits repeat at one height. Furniture — the panel edge, a glare
        // band, the OK badge — sits well off that height, so the median is a
        // far better reference than the tallest thing in frame.
        val ceiling = all.maxOf { it.height() }
        val tallish = all.filter { it.height() >= ceiling * 0.25 }
        val heights = tallish.map { it.height() }.sorted()
        val med = heights[heights.size / 2]

        val digits = tallish.filter {
            it.height() >= med * 0.78 && it.height() <= med * 1.22 && it.width() <= med * 1.2
        }
        val tallest = med
        log.append("shapes ${all.size}, digit height ~${med}px, digits ${digits.size}\n")
        if (digits.isEmpty()) return Result(emptyList(), log.toString())

        // Group into rows by vertical overlap.
        val rows = ArrayList<ArrayList<Rect>>()
        for (d in digits.sortedBy { it.centerY() }) {
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(row[0].centerY() - d.centerY()) < tallest * 0.55) {
                row.add(d)
            } else {
                rows.add(arrayListOf(d))
            }
        }

        val values = ArrayList<Pair<Int, Int>>()  // value, y-centre in source coords
        for (row in rows) {
            val sb = StringBuilder()
            var okRow = true
            for (d in row.sortedBy { it.left }) {
                val digit = decode(raw, d)
                if (digit == null) { okRow = false; break }
                sb.append(digit)
            }
            val yCentre = (row[0].centerY() / scale).toInt() + crop.top
            if (okRow && sb.isNotEmpty() && sb.length <= 3) {
                val v = sb.toString().toIntOrNull()
                if (v != null) {
                    values.add(v to yCentre)
                    log.append("row y=$yCentre → $sb\n")
                } else okRow = false
            }
            if (!okRow) log.append("row y=$yCentre → unreadable\n")
        }

        if (values.isEmpty()) return Result(emptyList(), log.toString())

        // Assign each decoded row to the field whose label sits nearest it.
        val ordered = if (rowAnchors.size == 3) {
            rowAnchors.map { anchor ->
                values.minByOrNull { kotlin.math.abs(it.second - anchor) }?.first ?: -1
            }
        } else {
            values.sortedBy { it.second }.map { it.first }
        }

        return Result(ordered.filter { it >= 0 }, log.toString())
    }

    private fun clampRoi(r: Rect, w: Int, h: Int): Rect? {
        val out = Rect(
            r.left.coerceIn(0, w - 1),
            r.top.coerceIn(0, h - 1),
            r.right.coerceIn(1, w),
            r.bottom.coerceIn(1, h)
        )
        return if (out.width() < 20 || out.height() < 20) null else out
    }

    /**
     * Adaptive threshold against a local average.
     *
     * A single global cutoff fails on these photos: the LCD carries a strong
     * brightness gradient, so a threshold that separates digits from panel at
     * the top classifies the whole darker bottom of the panel as ink, and the
     * lower rows disappear into one blob. Comparing each pixel with the mean of
     * its own neighbourhood removes the gradient.
     */
    private fun adaptive(bmp: Bitmap): Bin {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }

        // Integral image so the window mean is O(1) per pixel.
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }

        val r = kotlin.math.max(7, h / 6)
        val on = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = kotlin.math.max(0, y - r)
            val y1 = kotlin.math.min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = kotlin.math.max(0, x - r)
                val x1 = kotlin.math.min(w - 1, x + r)
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
