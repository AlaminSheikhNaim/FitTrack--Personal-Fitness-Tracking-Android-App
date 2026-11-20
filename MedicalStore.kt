package com.example.fittrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import java.io.File

data class MedicalDoc(
    val id: Long,
    val title: String,
    val fileName: String,
    val mime: String,
    val reportDay: String,     // date of the report (yyyy-MM-dd), chosen by the user
    val uploadedMs: Long,      // date and time it was uploaded (saved automatically)
    val path: String
)

class MedicalStore(private val ctx: Context) : SQLiteOpenHelper(ctx, "medical.db", null, 1) {

    val docs = mutableStateListOf<MedicalDoc>()
    private val dir = File(ctx.filesDir, "medical").apply { mkdirs() }

    init { refresh() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE docs (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, " +
                    "file_name TEXT NOT NULL, mime TEXT NOT NULL, report_day TEXT NOT NULL, " +
                    "uploaded_ms INTEGER NOT NULL, path TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun refresh() {
        docs.clear()
        readableDatabase.rawQuery(
            "SELECT id, title, file_name, mime, report_day, uploaded_ms, path FROM docs " +
                    "ORDER BY report_day DESC, uploaded_ms DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                docs.add(MedicalDoc(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getLong(5), c.getString(6)))
            }
        }
    }

    fun displayName(uri: Uri): String {
        var name = "document"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
        } catch (e: Exception) { }
        return name
    }

    // Copies the file into the app and saves it with the report date and the upload time
    fun add(uri: Uri, title: String, reportDay: String): Boolean {
        return try {
            val name = displayName(uri)
            val now = System.currentTimeMillis()
            val file = File(dir, "${now}_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            ctx.contentResolver.openInputStream(uri)?.use { i -> file.outputStream().use { i.copyTo(it) } }
                ?: return false
            val mime = ctx.contentResolver.getType(uri) ?: "*/*"
            val v = ContentValues().apply {
                put("title", title.ifBlank { name }); put("file_name", name); put("mime", mime)
                put("report_day", reportDay); put("uploaded_ms", now); put("path", file.path)
            }
            writableDatabase.insert("docs", null, v)
            refresh()
            true
        } catch (e: Exception) { false }
    }

    fun update(id: Long, title: String, reportDay: String) {
        val v = ContentValues().apply { put("title", title); put("report_day", reportDay) }
        writableDatabase.update("docs", v, "id = ?", arrayOf(id.toString()))
        refresh()
    }

    fun delete(doc: MedicalDoc) {
        File(doc.path).delete()
        writableDatabase.delete("docs", "id = ?", arrayOf(doc.id.toString()))
        refresh()
    }
}
