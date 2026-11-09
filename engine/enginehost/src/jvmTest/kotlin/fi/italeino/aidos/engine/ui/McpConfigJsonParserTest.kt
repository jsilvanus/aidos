package fi.italeino.aidos.engine.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpConfigJsonParserTest {

    @Test
    fun `parses Claude Desktop standard mcpServers with stdio and http`() {
        val input = """
        {
          "mcpServers": {
            "filesystem": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/data"],
              "env": {
                "DEBUG": "true"
              }
            },
            "remote-docs": {
              "url": "https://mcp.example.com/sse",
              "headers": {
                "Authorization": "Bearer test-key-123"
              }
            }
          }
        }
        """.trimIndent()

        val result = McpConfigJsonParser.parse(input)
        assertIs<McpJsonParseResult.Success>(result)
        val servers = result.servers
        assertEquals(2, servers.size)

        val fs = servers.first { it.name == "filesystem" }
        assertEquals("stdio", fs.transport)
        assertEquals("npx", fs.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "C:/data"), fs.args)
        assertEquals(mapOf("DEBUG" to "true"), fs.envOrHeaders)
        assertEquals(setOf("DESKTOP"), fs.profiles)

        val remote = servers.first { it.name == "remote-docs" }
        assertEquals("http", remote.transport)
        assertEquals("https://mcp.example.com/sse", remote.endpointUrl)
        assertEquals(mapOf("Authorization" to "Bearer test-key-123"), remote.envOrHeaders)
        assertTrue("MOBILE" in remote.profiles)
    }

    @Test
    fun `parses single server stdio json with markdown code fences and comments`() {
        val input = """
        ```json
        // Configuration for local python MCP server
        {
          "name": "python-calc",
          "command": "python",
          "args": ["calc_server.py", "--port", "5000"],
          "env": {
            "CALC_SECRET": "abc"
          }
        }
        ```
        """.trimIndent()

        val result = McpConfigJsonParser.parse(input)
        assertIs<McpJsonParseResult.Success>(result)
        val servers = result.servers
        assertEquals(1, servers.size)

        val server = servers.single()
        assertEquals("python-calc", server.name)
        assertEquals("stdio", server.transport)
        assertEquals("python", server.command)
        assertEquals(listOf("calc_server.py", "--port", "5000"), server.args)
        assertEquals(mapOf("CALC_SECRET" to "abc"), server.envOrHeaders)
    }

    @Test
    fun `parses single server http json with endpoint_url key`() {
        val input = """
        {
          "name": "weather-sse",
          "endpoint_url": "https://weather.service.org/mcp",
          "headers": {
            "X-Api-Key": "secret-987"
          }
        }
        """.trimIndent()

        val result = McpConfigJsonParser.parse(input)
        assertIs<McpJsonParseResult.Success>(result)
        val server = result.servers.single()
        assertEquals("weather-sse", server.name)
        assertEquals("http", server.transport)
        assertEquals("https://weather.service.org/mcp", server.endpointUrl)
        assertEquals(mapOf("X-Api-Key" to "secret-987"), server.envOrHeaders)
    }

    @Test
    fun `reports clear error when input is invalid or empty`() {
        val emptyResult = McpConfigJsonParser.parse("   ")
        assertIs<McpJsonParseResult.Error>(emptyResult)
        assertEquals("JSON input is empty", emptyResult.message)

        val malformedResult = McpConfigJsonParser.parse("{ invalid json }")
        assertIs<McpJsonParseResult.Error>(malformedResult)
        assertTrue(malformedResult.message.startsWith("Invalid JSON"))

        val incompleteResult = McpConfigJsonParser.parse("""{"name": "test-no-transport"}""")
        assertIs<McpJsonParseResult.Error>(incompleteResult)
    }
}
