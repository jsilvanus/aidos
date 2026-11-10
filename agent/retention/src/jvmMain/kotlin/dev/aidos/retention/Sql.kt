package dev.aidos.retention

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

internal fun <T> SqlDriver.queryList(
    sql: String,
    parameters: Int = 0,
    bind: (SqlPreparedStatement.() -> Unit)? = null,
    read: (SqlCursor) -> T,
): List<T> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { c ->
        val out = ArrayList<T>()
        while (c.next().value) out.add(read(c))
        QueryResult.Value(out)
    },
    parameters = parameters,
    binders = bind,
).value

internal fun SqlDriver.queryLong(
    sql: String,
    parameters: Int = 0,
    bind: (SqlPreparedStatement.() -> Unit)? = null,
): Long = queryList(sql, parameters, bind) { it.getLong(0) ?: 0L }.firstOrNull() ?: 0L

/** Executes a statement and returns the number of rows it changed. */
internal fun SqlDriver.update(
    sql: String,
    parameters: Int = 0,
    bind: (SqlPreparedStatement.() -> Unit)? = null,
): Long = execute(identifier = null, sql = sql, parameters = parameters, binders = bind).value

/**
 * Runs [block] in one SQLite transaction, rolling back if it throws. A bare [SqlDriver] has no
 * public transaction entry point, so this goes through a `TransacterImpl`, as the settings
 * module and the executor do.
 */
internal fun <R> SqlDriver.inTransaction(block: () -> R): R =
    object : TransacterImpl(this) {}.transactionWithResult { block() }
