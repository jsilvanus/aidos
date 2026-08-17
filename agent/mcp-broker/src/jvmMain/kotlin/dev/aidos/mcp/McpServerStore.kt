package dev.aidos.mcp

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.McpServerRegistration
import dev.aidos.mcp.core.McpTransport
import dev.aidos.mcp.policy.McpValidationResult
import dev.aidos.mcp.policy.validateHttpEndpoint as policyValidateHttpEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads `mcp_servers` (user scope, RFC-0054, `schema/user.sql`) into [McpServerRegistration]
 * values (RFC-0031, "Registration is user-scope; projects only request").
 *
 * This is **pure I/O against SQLite**. It never spawns a process, opens an HTTP connection, or
 * fetches a tool catalog — that only happens lazily at the first call that needs it (RFC-0031,
 * "Lifecycle: lazy start, idle stop"; D30: nothing connects on project open). Every returned
 * [McpServerRegistration.tools] is therefore empty; the catalog is fetched separately, at enable
 * time, from a live server.
 *
 * `secret_refs_json` (RFC-0035) holds *references* into the vault, never resolved values. This
 * class carries the whole map into [McpTransport.Stdio.secretRefs] / [McpTransport.Http.secretRefs]
 * unchanged; it never resolves, logs, or otherwise inspects what a reference points to.
 *
 * A row that fails validation is **reported, not dropped**: [loadAll] returns one
 * [McpServerLoadOutcome] per row, so a caller can log every rejection instead of silently losing
 * a misconfigured server.
 */
class McpServerStore(private val userDriver: SqlDriver) {

    /**
     * Reads every row in `mcp_servers` and validates it.
     *
     * [deviceProfile] is the platform this runtime is actually executing on — the same value
     * [dev.aidos.kernel.PlatformProfile] the daemon already threads through composition
     * (`RuntimeCompositionRoot`, `SqliteRunExecutor`). It is *not* the row's `profiles_json`
     * (RFC-0049's "where a server is available" set, returned separately on
     * [McpServerLoad.profiles]): [dev.aidos.mcp.policy.validateHttpEndpoint]'s loopback exemption
     * is a property of the device running the check, not of every profile a server merely claims
     * to support elsewhere.
     */
    fun loadAll(deviceProfile: PlatformProfile): List<McpServerLoadOutcome> =
        readRows().map { row -> loadRow(row, deviceProfile) }

    private fun loadRow(row: Row, deviceProfile: PlatformProfile): McpServerLoadOutcome =
        try {
            McpServerLoadOutcome.Loaded(buildLoad(row, deviceProfile))
        } catch (rejection: RowRejected) {
            McpServerLoadOutcome.Rejected(row.name, rejection.reason)
        }

    private fun buildLoad(row: Row, deviceProfile: PlatformProfile): McpServerLoad {
        val transport = buildTransport(row)
        if (transport is McpTransport.Http) {
            when (val result = policyValidateHttpEndpoint(transport.endpointUrl, deviceProfile.toMcpProfile())) {
                is McpValidationResult.Rejected -> throw RowRejected(result.reason)
                McpValidationResult.Ok -> Unit
            }
        }
        return McpServerLoad(
            registration = McpServerRegistration(serverId = row.name, transport = transport, tools = emptyList()),
            profiles = parseProfiles(row.profilesJson),
        )
    }

    /**
     * Builds the transport half of the registration. The CHECK constraints in `schema/user.sql`
     * already guarantee a stored row cannot mix `command` and `endpoint_url`, but this class does
     * not trust that guarantee blindly (a future migration or a driver that skips constraint
     * enforcement should fail a row here, not corrupt a registration silently) — hence the
     * explicit checks below even though they are normally unreachable.
     */
    private fun buildTransport(row: Row): McpTransport {
        val secretRefs = parseSecretRefs(row.secretRefsJson)
        return when (row.transport) {
            "stdio" -> {
                val command = row.command
                    ?: throw RowRejected("stdio row '${row.name}' has no command")
                if (row.endpointUrl != null) {
                    throw RowRejected("stdio row '${row.name}' also has endpoint_url set")
                }
                McpTransport.Stdio(
                    command = command,
                    args = parseArgs(row.argsJson),
                    secretRefs = secretRefs,
                )
            }
            "http" -> {
                val endpointUrl = row.endpointUrl
                    ?: throw RowRejected("http row '${row.name}' has no endpoint_url")
                if (row.command != null) {
                    throw RowRejected("http row '${row.name}' also has command set")
                }
                McpTransport.Http(
                    endpointUrl = endpointUrl,
                    // The header a secret is injected under is the map key; when a row configures
                    // exactly one, it names the header. With none, or with several, there is no
                    // single answer and the RFC's default stands.
                    authHeaderName = secretRefs.keys.singleOrNull()?.takeIf { it.isNotBlank() }
                        ?: "Authorization",
                    secretRefs = secretRefs,
                )
            }
            else -> throw RowRejected("row '${row.name}' has unknown transport '${row.transport}'")
        }
    }

    private fun parseProfiles(json: String): Set<PlatformProfile> {
        val array = runCatching { Json.parseToJsonElement(json).jsonArray }
            .getOrElse { throw RowRejected("profiles_json is not a JSON array: $json") }
        if (array.isEmpty()) {
            throw RowRejected("profiles_json names no profiles: $json")
        }
        return array.map { element ->
            val name = runCatching { element.jsonPrimitive.content }
                .getOrElse { throw RowRejected("profiles_json contains a non-string entry: $json") }
            runCatching { PlatformProfile.valueOf(name) }
                .getOrElse { throw RowRejected("profiles_json names an unknown profile '$name'") }
        }.toSet()
    }

    private fun parseArgs(json: String): List<String> {
        val array = runCatching { Json.parseToJsonElement(json).jsonArray }
            .getOrElse { throw RowRejected("args_json is not a JSON array: $json") }
        return array.map { element ->
            runCatching { element.jsonPrimitive.content }
                .getOrElse { throw RowRejected("args_json contains a non-string entry: $json") }
        }
    }

    /**
     * `secret_refs_json` maps a destination name (env var for stdio, header for http) to a vault
     * reference (RFC-0035, `schema/user.sql`'s own comment on the column). The map is carried
     * through whole: values are references, never secrets, so nothing here resolves, logs, or
     * inspects them — that happens at connect time, in the layer holding vault access.
     */
    private fun parseSecretRefs(json: String): Map<String, String> {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }
            .getOrElse { throw RowRejected("secret_refs_json is not a JSON object: $json") }
        return obj.entries.associate { (destinationName, referenceElement) ->
            if (destinationName.isBlank()) {
                throw RowRejected("secret_refs_json has a blank destination name: $json")
            }
            val reference = runCatching { referenceElement.jsonPrimitive.content }
                .getOrElse {
                    throw RowRejected("secret_refs_json's value for '$destinationName' is not a string: $json")
                }
            destinationName to reference
        }
    }

    private fun readRows(): List<Row> =
        userDriver.executeQuery(
            identifier = null,
            sql = "SELECT name, transport, command, args_json, endpoint_url, profiles_json, secret_refs_json " +
                "FROM mcp_servers ORDER BY name",
            mapper = { cursor ->
                val rows = mutableListOf<Row>()
                while (cursor.next().value) {
                    rows.add(
                        Row(
                            name = cursor.getString(0)!!,
                            transport = cursor.getString(1)!!,
                            command = cursor.getString(2),
                            argsJson = cursor.getString(3)!!,
                            endpointUrl = cursor.getString(4),
                            profilesJson = cursor.getString(5)!!,
                            secretRefsJson = cursor.getString(6)!!,
                        )
                    )
                }
                QueryResult.Value(rows)
            },
            parameters = 0,
        ) {}.value

    private data class Row(
        val name: String,
        val transport: String,
        val command: String?,
        val argsJson: String,
        val endpointUrl: String?,
        val profilesJson: String,
        val secretRefsJson: String,
    )

    /** Internal control-flow only — never escapes [loadRow]. */
    private class RowRejected(val reason: String) : Exception(reason)
}

/**
 * A successfully loaded registration together with the profiles the row declared it available on
 * (RFC-0049). [McpServerRegistration] itself carries no profile field — that vocabulary is
 * [PlatformProfile], which belongs to the kernel-bound layer (RFC-0031, "Implementation
 * Layering") — so this wrapper is where the two meet.
 */
data class McpServerLoad(
    val registration: McpServerRegistration,
    val profiles: Set<PlatformProfile>,
)

/** The result of loading one `mcp_servers` row. Rejections are reported, never silently dropped. */
sealed interface McpServerLoadOutcome {
    data class Loaded(val load: McpServerLoad) : McpServerLoadOutcome
    data class Rejected(val serverId: String, val reason: String) : McpServerLoadOutcome
}
