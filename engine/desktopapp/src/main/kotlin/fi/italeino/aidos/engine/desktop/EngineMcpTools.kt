package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.agent.EngineTool
import fi.italeino.aidos.engine.agent.EngineToolSource
import kotlinx.serialization.json.JsonObject

/**
 * The Engine's agent loop on the adopted MCP operations (RFC-0115, RFC-0031): each adopted operation becomes an
 * [EngineTool] named `<server>:<operation>`, which the Engine runs itself through [McpManager]. Every call waits for the
 * user: the adoption record keeps an operation's name, description and schema but not its server-declared annotations
 * (which are untrusted claims anyway), so nothing is known to be read only. A later "allow without asking" choice would
 * set [EngineTool.readOnly]; it is deliberately not inferred from the server.
 */
class EngineMcpTools(private val manager: McpManager) : EngineToolSource {
    override suspend fun toolsFor(owner: String): List<EngineTool> = manager.adoptedTools().map { t ->
        EngineTool(
            name = "${t.serverId}:${t.spec.name}",
            description = t.spec.description,
            inputSchema = t.spec.inputSchema,
            readOnly = false,
        ) { args: JsonObject -> manager.callTool(t.serverId, t.spec.name, args) }
    }
}
