package com.stressdetector.mobile.lifecycle

import androidx.sqlite.db.SupportSQLiteDatabase
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** SQLite serialization stays separate from archive transport and media publication. */
internal class PortableArchiveRows(private val storage: EncryptedStorageService, private val userId: String) {
    fun readSchema(sqlite: SupportSQLiteDatabase): Map<String, List<ArchiveColumn>> = PortableArchiveFormat.TABLES.associateWith { table ->
        sqlite.query("PRAGMA table_info(\"$table\")").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    add(
                        ArchiveColumn(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                            defaultValue = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex),
                            primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                        )
                    )
                }
            }.also { require(it.isNotEmpty()) { "Missing scoped table: $table" } }
        }
    }

    fun writeTable(
        sqlite: SupportSQLiteDatabase,
        table: String,
        columns: List<ArchiveColumn>,
        graph: PortableArchiveGraph,
        sink: OutputStream
    ): Long {
        var count = 0L
        val writer = BufferedWriter(OutputStreamWriter(sink, StandardCharsets.UTF_8))
        sqlite.query("SELECT * FROM \"$table\"").use { cursor ->
            require(cursor.columnNames.toList() == columns.map(ArchiveColumn::name)) { "Table columns changed during export" }
            while (cursor.moveToNext()) {
                val line = PortableArchiveFormat.encodeRow(cursor)
                val row = PortableArchiveFormat.decodeRow(line, columns)
                if (!graph.include(table, row)) continue
                graph.record(table, row)
                writer.write(line)
                writer.newLine()
                count++
            }
        }
        writer.flush()
        return count
    }

    fun restoreTable(sqlite: SupportSQLiteDatabase, table: String, columns: List<ArchiveColumn>, stagePath: String) {
        val names = columns.joinToString(",") { "\"${it.name}\"" }
        val placeholders = List(columns.size) { "?" }.joinToString(",")
        val statement = "INSERT INTO \"$table\" ($names) VALUES ($placeholders)"
        readLines(stagePath) { line ->
            val row = PortableArchiveFormat.decodeRow(line, columns)
            sqlite.execSQL(statement, row.toTypedArray())
        }
    }

    fun readLines(stagePath: String, accept: (String) -> Unit) {
        val parser = LineOutputStream(accept)
        storage.copyEncryptedFile(userId, stagePath, parser)
        parser.finish()
    }

    fun requireEmpty(sqlite: SupportSQLiteDatabase) {
        require(isEmpty(sqlite)) { "Pemulihan membutuhkan History, chat, dan Verbatim+ lokal yang kosong." }
    }

    fun isEmpty(sqlite: SupportSQLiteDatabase): Boolean = PortableArchiveFormat.TABLES.all { table ->
        sqlite.query("SELECT 1 FROM \"$table\" LIMIT 1").use { cursor -> !cursor.moveToFirst() }
    }
}

private class LineOutputStream(private val accept: (String) -> Unit) : OutputStream() {
    private val line = ByteArrayOutputStream()
    override fun write(value: Int) {
        if (value == '\n'.code) emit() else append(byteArrayOf(value.toByte()), 0, 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var start = offset
        val end = offset + length
        for (index in offset until end) {
            if (bytes[index] == '\n'.code.toByte()) {
                append(bytes, start, index - start)
                emit()
                start = index + 1
            }
        }
        append(bytes, start, end - start)
    }

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        require(line.size().toLong() + length <= MAX_LINE_BYTES) { "Archive row exceeds size limit" }
        line.write(bytes, offset, length)
    }

    private fun emit() {
        require(line.size() > 0) { "Blank archive row" }
        accept(decodeArchiveUtf8(line.toByteArray()))
        line.reset()
    }

    fun finish() {
        require(line.size() == 0) { "Unterminated archive row" }
    }

    private companion object {
        const val MAX_LINE_BYTES = 16L * 1024L * 1024L
    }
}
