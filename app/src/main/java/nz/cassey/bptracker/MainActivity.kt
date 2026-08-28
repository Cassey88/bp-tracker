package nz.cassey.bptracker

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var adapter: ReadingAdapter

    private lateinit var btnResting: Button
    private lateinit var btnExercise: Button
    private lateinit var etSys: EditText
    private lateinit var etDia: EditText
    private lateinit var etPulse: EditText
    private lateinit var tvEmpty: TextView

    /**
     * Deliberately not persisted: resets to resting on every app start so a
     * stale "exercise" from yesterday can't mislabel this morning's reading.
     */
    private var state = State.RESTING

    companion object {
        private const val REQ_IMPORT = 2
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

        // Jump to the next field once the value can't grow any further —
        // "130" fills three digits, "79" can't extend to a valid diastolic.
        autoAdvance(etSys, SYS_MAX, etDia)
        autoAdvance(etDia, DIA_MAX, etPulse)
        autoAdvance(etPulse, PULSE_MAX, null)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

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
                val v = s.toString().toIntOrNull() ?: return
                if (s.length >= 3 || v * 10 > max) {
                    if (next != null) {
                        next.requestFocus()
                    } else {
                        hideKeyboard(et)
                    }
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

        etSys.text.clear(); etDia.text.clear(); etPulse.text.clear()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Export CSV")
        menu.add(0, 2, 1, "Import CSV")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> doExport()
            2 -> pickImport()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

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

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_IMPORT && res == RESULT_OK) data?.data?.let { doImport(it) }
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

    /** dd/MM/yyyy for the screen; the database and CSV stay ISO. */
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
