package nz.cassey.bptracker

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var adapter: ReadingAdapter

    private lateinit var btnResting: Button
    private lateinit var btnExercise: Button
    private lateinit var etSys: EditText
    private lateinit var etDia: EditText
    private lateinit var etPulse: EditText
    private lateinit var tvEmpty: TextView

    /** Resets to resting on every app start — see README. */
    private var state = State.RESTING

    private var scanFile: File? = null

    /** True while OCR writes the fields, so auto-advance stays out of the way. */
    private var filling = false

    /** Raw text and candidate list from the last scan, for diagnosis. */
    private var lastRaw: String = ""
    private var lastDiag: String = ""

    companion object {
        private const val REQ_PHOTO = 1
        private const val REQ_IMPORT = 2
        private const val REQ_GALLERY = 3
        private const val BLUE = 0xFF0078D4.toInt()
        private const val GRAY = 0xFFE1E1E1.toInt()
        private const val DARK = 0xFF201F1E.toInt()
        private val ISO_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val ISO_TIME = SimpleDateFormat("HH:mm", Locale.US)
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

        private const val SYS_MAX = 280
        private const val DIA_MAX = 200
        private const val PULSE_MAX = 220
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db(this)

        btnResting = findViewById(R.id.btnResting)
        btnExercise = findViewById(R.id.btnExercise)
        etSys = findViewById(R.id.etSys)
        etDia = findViewById(R.id.etDia)
        etPulse = findViewById(R.id.etPulse)
        tvEmpty = findViewById(R.id.tvEmpty)

        btnResting.setOnClickListener { state = State.RESTING; paintState() }
        btnExercise.setOnClickListener { state = State.EXERCISE; paintState() }
        paintState()

        autoAdvance(etSys, SYS_MAX, etDia)
        autoAdvance(etDia, DIA_MAX, etPulse)
        autoAdvance(etPulse, PULSE_MAX, null)

        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        findViewById<Button>(R.id.btnGallery).setOnClickListener { pickFromGallery() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        findViewById<TextView>(R.id.btnMenu).setOnClickListener { v ->
            val pm = PopupMenu(this, v)
            pm.menu.add(0, 1, 0, "Export CSV")
            pm.menu.add(0, 2, 1, "Import CSV")
            pm.menu.add(0, 3, 2, "User guide")
            pm.menu.add(0, 4, 3, "Last scan")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { doExport(); true }
                    2 -> { pickImport(); true }
                    3 -> { showGuide(); true }
                    4 -> { showLastScan(); true }
                    else -> false
                }
            }
            pm.show()
        }

        val list = findViewById<ListView>(R.id.list)
        adapter = ReadingAdapter()
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(adapter.getItem(pos))
            true
        }

        refresh()
    }

    private fun paintState() {
        val active = if (state == State.RESTING) btnResting else btnExercise
        val idle = if (state == State.RESTING) btnExercise else btnResting
        active.backgroundTintList = ColorStateList.valueOf(BLUE)
        active.setTextColor(Color.WHITE)
        idle.backgroundTintList = ColorStateList.valueOf(GRAY)
        idle.setTextColor(DARK)
    }

    private fun autoAdvance(et: EditText, max: Int, next: EditText?) {
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                if (filling) return
                val v = s.toString().toIntOrNull() ?: return
                if (s.length >= 3 || v * 10 > max) {
                    if (next != null) next.requestFocus() else hideKeyboard(et)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun refresh() {
        adapter.replace(db.all())
        tvEmpty.visibility = if (adapter.count == 0) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------- scan

    private fun clearFields() {
        filling = true
        etSys.text.clear(); etDia.text.clear(); etPulse.text.clear()
        filling = false
    }

    private fun scan() {
        clearFields()
        etSys.requestFocus()
        val dir = File(cacheDir, "ocr").apply { mkdirs() }
        scanFile = File(dir, "scan.jpg").also { it.delete() }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", scanFile!!)

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        packageManager.queryIntentActivities(intent, 0).forEach {
            grantUriPermission(
                it.activityInfo.packageName, uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQ_PHOTO)
        } catch (e: ActivityNotFoundException) {
            toast("No camera app found")
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_PHOTO -> {
                val f = scanFile
                scanFile = null
                if (res == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                    runOcr(f)
                } else {
                    f?.delete()
                }
            }
            REQ_GALLERY -> if (res == RESULT_OK) data?.data?.let { uri ->
                val bmp = decodeScaled(uri)
                if (bmp != null) ocr(bmp) else toast("Couldn't open that image")
            }
            REQ_IMPORT -> if (res == RESULT_OK) data?.data?.let { doImport(it) }
        }
    }

    private fun pickFromGallery() {
        clearFields()
        etSys.requestFocus()
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        try {
            startActivityForResult(i, REQ_GALLERY)
        } catch (e: ActivityNotFoundException) {
            toast("No gallery app found")
        }
    }

    private fun diagText(pass: String, all: List<Cand>, big: List<Cand>, outcome: String): String {
        val fmt = { c: Cand -> "${c.v} (y=${c.y}, h=${c.h})" }
        return buildString {
            append("Pass: ").append(pass).append("\n\n")
            append("Result: ").append(outcome).append("\n\n")
            append("Large groups, top to bottom:\n")
            append(if (big.isEmpty()) "  none\n" else big.joinToString("\n") { "  " + fmt(it) } + "\n")
            append("\nAll number groups found:\n")
            append(if (all.isEmpty()) "  none\n" else all.joinToString("\n") { "  " + fmt(it) } + "\n")
            append("\nRaw recognised text:\n")
            append(lastRaw)
        }
    }

    private fun showLastScan() {
        AlertDialog.Builder(this)
            .setTitle("Last scan")
            .setMessage(if (lastDiag.isBlank()) "No scan yet." else lastDiag)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showGuide() {
        val guide = """
😴  Resting — measured after sitting quietly for a few minutes. Rated.

🚴  Exercise — measured soon after exercise (e.g. qigong). Not rated, because the bands below only apply to resting readings.

Ratings (the worse of the two numbers decides):

👍  Good — below 120 and below 80
🆗  OK — 120–129 or 80–84
😐  Watch — 130–139 or 85–89
😞  High — 140 or more, or 90 or more

–  (dimmed) Exercise reading, not rated

Tips:

📸 Camera photographs the monitor and fills the numbers in. 🖼️ Gallery does the same from a photo you already have. Always check the numbers before Save — the LCD digits can be misread.

Long-press a reading to delete it. Export and Import CSV are in the ⋮ menu; exports go to your Downloads folder.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("User guide")
            .setMessage(guide)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Two-pass OCR. Pass 1 is the photo as taken. If that doesn't yield a
     * plausible SYS/DIA/PULSE triple, pass 2 re-runs on a grayscale,
     * contrast-boosted, slightly softened copy — softening closes the gaps
     * between LCD segments, which is what usually trips the recogniser.
     */
    private fun runOcr(file: File) {
        val bmp = decodeScaled(file) ?: run {
            toast("Couldn't read the photo — type the numbers")
            file.delete(); return
        }
        file.delete()
        ocr(bmp)
    }

    private fun ocr(bmp: Bitmap) {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r1 ->
                if (applyOcr(r1, "1 — original")) {
                    showLastScan()
                } else {
                    client.process(InputImage.fromBitmap(enhance(bmp), 0))
                        .addOnSuccessListener { r2 ->
                            applyOcr(r2, "2 — enhanced")
                            showLastScan()
                        }
                        .addOnFailureListener { toast("Couldn't read the display — type the numbers") }
                }
            }
            .addOnFailureListener { toast("Couldn't read the photo — type the numbers") }
    }

    private fun decodeScaled(file: File): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, probe)
        if (probe.outWidth <= 0) return null
        var sample = 1
        while (maxOf(probe.outWidth, probe.outHeight) / (sample * 2) >= 1600) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun decodeScaled(uri: Uri): Bitmap? = try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, probe) }
        if (probe.outWidth <= 0) null else {
            var sample = 1
            while (maxOf(probe.outWidth, probe.outHeight) / (sample * 2) >= 1600) sample *= 2
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) { null }

    private fun enhance(src: Bitmap): Bitmap {
        // Soften: down to 55% and back up, bilinear both ways.
        val small = Bitmap.createScaledBitmap(src, (src.width * 0.55).toInt(), (src.height * 0.55).toInt(), true)
        val soft = Bitmap.createScaledBitmap(small, src.width, src.height, true)

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.9f
        val offset = -95f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        Canvas(out).drawBitmap(soft, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private data class Cand(val v: Int, val x: Int, val y: Int, val h: Int)

    /** @return true if the fields were filled with a plausible triple. */
    private fun applyOcr(text: Text, pass: String): Boolean {
        lastRaw = text.text.ifBlank { "(nothing recognised)" }
        val cands = ArrayList<Cand>()

        fun harvest(raw: String, box: android.graphics.Rect?) {
            if (box == null) return
            val cleaned = raw
                .replace('O', '0').replace('o', '0').replace('D', '0')
                .replace('l', '1').replace('I', '1').replace('i', '1').replace('|', '1')
                .replace('S', '5').replace('s', '5')
                .replace('B', '8').replace('g', '9').replace('q', '9')
                .replace('Z', '2').replace('z', '2').replace('G', '6').replace('b', '6')
            for (m in Regex("\\d{2,3}").findAll(cleaned)) {
                m.value.toIntOrNull()?.let {
                    cands.add(Cand(it, box.centerX(), box.centerY(), box.height()))
                }
            }
        }

        // Harvest from both granularities: lines keep merged digits together,
        // elements catch groups a line lumped in with other symbols.
        for (block in text.textBlocks) for (line in block.lines) {
            harvest(line.text, line.boundingBox)
            for (el in line.elements) harvest(el.text, el.boundingBox)
        }
        if (cands.isEmpty()) {
            lastDiag = diagText(pass, emptyList(), emptyList(), "no 2-3 digit groups found")
            return false
        }

        // A photo of the monitor usually catches other text too — a diary page,
        // a phone timestamp, packaging. Those numbers are the wrong size and in
        // the wrong place, so instead of taking whatever is tallest, find the
        // group that actually looks like a readout: three or more numbers of
        // similar glyph height, stacked in one column.
        val clusters = ArrayList<ArrayList<Cand>>()
        for (c in cands.sortedByDescending { it.h }) {
            val home = clusters.firstOrNull { cl ->
                val ref = cl[0]
                val ratio = c.h.toDouble() / ref.h
                ratio in 0.65..1.55 && abs(c.x - ref.x) <= ref.h * 2.2
            }
            if (home != null) home.add(c) else clusters.add(arrayListOf(c))
        }

        // Prefer a cluster holding a full readout; among those, the one with
        // the tallest digits, since the display dominates a well-framed shot.
        val best = clusters
            .filter { it.size >= 3 }
            .maxByOrNull { cl -> cl.sumOf { it.h }.toDouble() / cl.size }
            ?: clusters.maxByOrNull { cl -> cl.sumOf { it.h }.toDouble() / cl.size }
            ?: return false

        val big = best.sortedBy { it.y }
            .fold(ArrayList<Cand>()) { acc, c ->
                val dup = acc.any { abs(it.y - c.y) < c.h / 2 && it.v == c.v }
                if (!dup) acc.add(c)
                acc
            }

        val picked = big.take(3)
        if (picked.size < 3) {
            lastDiag = diagText(pass, cands, big, "only ${picked.size} number(s) in the readout column — need 3")
            return false
        }

        val (a, b, c) = Triple(picked[0].v, picked[1].v, picked[2].v)

        // Refuse to fill nonsense. A wrong number that looks plausible is
        // worse than no number, because it can be saved without noticing.
        val sensible = a in 80..SYS_MAX && b in 40..150 && b < a && c in 30..PULSE_MAX
        if (!sensible) {
            lastDiag = diagText(pass, cands, big, "picked $a / $b / $c — outside sensible ranges")
            return false
        }
        lastDiag = diagText(pass, cands, big, "filled $a / $b / $c")

        filling = true
        etSys.setText(a.toString())
        etDia.setText(b.toString())
        etPulse.setText(c.toString())
        filling = false

        etSys.requestFocus()
        etSys.setSelection(etSys.text.length)
        hideKeyboard(etSys)
        return true
    }

    // ------------------------------------------------------------------- save

    private fun save() {
        val sys = etSys.text.toString().trim().toIntOrNull()
        val dia = etDia.text.toString().trim().toIntOrNull()
        val pulse = etPulse.text.toString().trim().toIntOrNull()

        if (sys == null || dia == null || pulse == null) {
            toast("Enter systolic, diastolic and pulse")
            return
        }
        if (sys !in 50..SYS_MAX || dia !in 30..DIA_MAX || pulse !in 25..PULSE_MAX) {
            toast("Those numbers look out of range — check them")
            return
        }
        if (dia >= sys) {
            toast("Diastolic should be lower than systolic")
            return
        }

        val now = Calendar.getInstance().time
        val r = Reading(
            date = ISO_DATE.format(now),
            time = ISO_TIME.format(now),
            state = state,
            sys = sys, dia = dia, pulse = pulse,
            rating = Rating.of(state, sys, dia)
        )
        db.upsert(r)

        clearFields()
        etSys.requestFocus()

        refresh()
        toast("Saved  ${Rating.emoji(r.rating)}  ${Rating.label(r.rating)}")
    }

    private fun confirmDelete(r: Reading) {
        AlertDialog.Builder(this)
            .setTitle("Delete reading")
            .setMessage("${displayDate(r.date)} ${r.time} — ${r.sys}/${r.dia}")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                db.delete(r.id)
                refresh()
            }
            .show()
    }

    // --------------------------------------------------------- export/import

    /** Writes straight into Downloads — no share sheet. */
    private fun doExport() {
        val rows = db.all()
        if (rows.isEmpty()) { toast("Nothing to export yet"); return }

        val name = "bp-${STAMP.format(Calendar.getInstance().time)}.csv"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        try {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: run { toast("Could not write to Downloads"); return }
            contentResolver.openOutputStream(uri)?.use {
                it.write(Csv.export(rows).toByteArray(Charsets.UTF_8))
            }
            toast("${rows.size} readings → Downloads/$name")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
        try {
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: ActivityNotFoundException) {
            toast("No file picker available")
        }
    }

    private fun doImport(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text.isNullOrBlank()) { toast("That file is empty"); return }

            val before = db.count()
            val parsed = Csv.parse(text)
            parsed.rows.forEach { db.upsert(it) }
            val added = db.count() - before

            refresh()
            val updated = parsed.rows.size - added
            val msg = buildString {
                append("$added new, $updated updated")
                if (parsed.skipped > 0) append(", ${parsed.skipped} skipped")
            }
            toast(msg)
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ misc

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun displayDate(iso: String): String {
        val p = iso.split('-')
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
    }

    // --------------------------------------------------------------- adapter

    private inner class ReadingAdapter : BaseAdapter() {
        private var items: List<Reading> = emptyList()

        fun replace(list: List<Reading>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = items[pos].id

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.row_reading, parent, false)
            val r = items[pos]

            v.findViewById<TextView>(R.id.rowState).text = State.emoji(r.state)
            v.findViewById<TextView>(R.id.rowWhen).text =
                "${displayDate(r.date)}  ${r.time}"
            v.findViewById<TextView>(R.id.rowValues).text =
                "${r.sys}/${r.dia}   \u2665 ${r.pulse}"

            val rating = v.findViewById<TextView>(R.id.rowRating)
            rating.text = Rating.emoji(r.rating)
            rating.alpha = if (r.rating == Rating.NONE) 0.35f else 1f

            return v
        }
    }
}
