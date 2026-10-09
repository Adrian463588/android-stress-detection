package com.stressdetector.mobile.lifecycle

import androidx.sqlite.db.SupportSQLiteDatabase

/** Private recovery ledger; receipts commit atomically with imported Room rows, never user data. */
internal class PortableRestoreCommits {
    fun markCommitted(database: SupportSQLiteDatabase, token: String) {
        check(database.inTransaction()) { "Restore receipt requires the import transaction" }
        database.execSQL("CREATE TABLE IF NOT EXISTS portable_restore_commits (token TEXT NOT NULL PRIMARY KEY)")
        database.execSQL("INSERT INTO portable_restore_commits (token) VALUES (?)", arrayOf(token))
    }

    fun isCommitted(database: SupportSQLiteDatabase, token: String): Boolean {
        if (!exists(database)) return false
        return database.query("SELECT token FROM portable_restore_commits WHERE token = ?", arrayOf(token)).use {
            it.moveToFirst()
        }
    }

    fun clearRecovered(database: SupportSQLiteDatabase) {
        check(database.inTransaction()) { "Restore receipt cleanup requires a transaction" }
        if (exists(database)) database.execSQL("DELETE FROM portable_restore_commits")
    }

    private fun exists(database: SupportSQLiteDatabase): Boolean = database.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'portable_restore_commits'"
    ).use { it.moveToFirst() }
}
