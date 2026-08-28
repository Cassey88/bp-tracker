package nz.cassey.bptracker

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var adapter: ReadingAdapter

    private lateinit var btnState: Button
    private lateinit var etSys: EditText
    private lateinit var etDia: EditText
    private lateinit var etPulse: EditText
    private lateinit var ivPreview: ImageView
    private lateinit var tvEmpty: TextView

    /**
     * Deliberately not persisted. If it remembered "exercise" from yesterday
     * you would silently mislabel the next morning's resting reading, so the
     * toggle resets to resting every time the app starts.
     */
    private var state = State.RESTING

    private var pendingPhoto: Uri? = null
    private var attachedPhoto: Uri? = null

    companion object {
        private const val REQ_PHOTO = 1
        private const val REQ_IMPORT = 2
        private val ISO_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val ISO_TIME = SimpleDateFormat("HH:mm", Locale.US)
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db(this)

        btnState = findViewById(R.id.btnState)
        etSys = findViewById(R.id.etSys)
        etDia = findViewById(R.id.etDia)
        etPulse = findViewById(R.id.etPulse)
        ivPreview = findViewById(R.id.ivPreview)
        tvEmpty = findViewById(R.id.tvEmpty)

        btnState.setOnClickListener {
            state = if (state == State.RESTING) State.EXERCISE else State.RESTING
            paintState()
        }
        paintState()

        findViewById<Button>(R.id.btnCamera).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        val list = findViewById<ListView>(R.id.list)
        adapter = ReadingAdapter()
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(adapter.getItem(pos))
            true
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.getItem(pos).photo?.let { openPhoto(Uri.parse(it)) }
        }

        refresh()
    }

    private fun paintState() {
        btnState.text = "${State.emoji(state)}  ${State.label(state)}"
    }

    private fun refresh() {
        adapter.replace(db.all())
        tvEmpty.visibility = if (adapter.count == 0) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- capture

    private fun takePhoto() {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "BP_${STAMP.format(Calendar.getInstance().time)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BPTracker")
        }
        pendingPhoto = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (pendingPhoto == null) {
            toast("Could not create an image file")
            return
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, pendingPhoto)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        // Some camera apps ignore the implicit grant, so grant explicitly too.
        packageManager.queryIntentActivities(intent, 0).forEach {
            grantUriPermission(
                it.activityInfo.packageName, pendingPhoto,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        try {
            startActivityForResult(intent, REQ_PHOTO)
        } catch (e: ActivityNotFoundException) {
            contentResolver.delete(pendingPhoto!!, null, null)
            pendingPhoto = null
            toast("No camera app found")
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_PHOTO -> {
                if (res == RESULT_OK && pendingPhoto != null) {
                    attachedPhoto = pendingPhoto
                    showPreview(attachedPhoto)
                    etSys.requestFocus()
                } else {
                    pendingPhoto?.let { contentResolver.delete(it, null, null) }
                }
                pendingPhoto = null
            }
            REQ_IMPORT -> {
                if (res == RESULT_OK) data?.data?.let { doImport(it) }
            }
        }
    }

    private fun showPreview(uri: Uri?) {
        if (uri == null) {
            ivPreview.visibility = View.GONE
            return
        }
        try {
            contentResolver.openInputStream(uri)?.use { ins ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                ivPreview.setImageBitmap(BitmapFactory.decodeStream(ins, null, opts))
                ivPreview.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            ivPreview.visibility = View.GONE
        }
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
        if (sys !in 50..280 || dia !in 30..200 || pulse !in 25..220) {
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
            rating = Rating.of(state, sys, dia),
            photo = attachedPhoto?.toString()
        )
        db.upsert(r)

        etSys.text.clear(); etDia.text.clear(); etPulse.text.clear()
        attachedPhoto = null
        showPreview(null)
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

            val share = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Blood pressure readings")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "Share $name"))

            toast("${rows.size} readings saved to Downloads/$name")
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

    private fun openPhoto(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("Photo is no longer available")
        }
    }

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
            // Unrated exercise rows are dimmed so a scan down the list only
            // compares like with like.
            rating.alpha = if (r.rating == Rating.NONE) 0.35f else 1f

            val cam = v.findViewById<TextView>(R.id.rowPhoto)
            cam.visibility = if (r.photo != null) View.VISIBLE else View.INVISIBLE

            return v
        }
    }
}
