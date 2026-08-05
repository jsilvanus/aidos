package dev.aidos.storage

/**
 * The three databases RFC-0040 defines. Each versions independently -- there is no global schema
 * version -- so each carries its own current version and its own schema script (`schema/`, the
 * canonical DDL; RFC-0040 Non-goals).
 */
enum class DatabaseKind(val schemaResource: String, val currentVersion: Int) {
    USER("user.sql", 1),
    VAULT("vault.sql", 1),
    PROJECT("project.sql", 1),
}
