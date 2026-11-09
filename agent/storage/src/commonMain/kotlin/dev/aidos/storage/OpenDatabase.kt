package dev.aidos.storage

import app.cash.sqldelight.db.SqlDriver

/** An opened database and what `MigrationRunner` found when it opened. */
data class OpenDatabase(val driver: SqlDriver, val result: OpenResult)
