#!/usr/bin/env python3
"""Small real subprocess MCP fixture used by StdioMcpClientTest."""
import json
import os
import sys


def reply(id_, result=None, error=None):
    msg = {"jsonrpc": "2.0", "id": id_}
    if error is not None:
        msg["error"] = error
    else:
        msg["result"] = result
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = json.loads(line)
        method = req.get("method")
        id_ = req.get("id")

        # A notification has no id and MUST NOT be answered. The SDK sends
        # notifications/initialized after the handshake; replying to it (even with an
        # error) puts an uncorrelatable frame on the wire.
        if id_ is None:
            continue

        if method == "initialize":
            # protocolVersion and capabilities are REQUIRED by MCP's InitializeResult.
            # The pre-SDK Aidos client only read serverInfo and tolerated their absence;
            # the official SDK deserializes strictly, so omitting them made every request
            # time out rather than fail loudly. Echo the client's protocolVersion so this
            # fixture keeps working as the negotiated version moves.
            requested = (req.get("params") or {}).get("protocolVersion", "2024-11-05")
            reply(id_, result={
                "protocolVersion": requested,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "fake-mcp-server", "version": "0.0.1"},
            })
        elif method == "tools/list":
            reply(id_, result={"tools": [
                {"name": "echo", "description": "echoes its input back", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}}},
                {"name": "env", "description": "returns child environment diagnostics", "inputSchema": {"type": "object"}},
                {"name": "hang", "description": "never returns", "inputSchema": {"type": "object"}},
            ]})
        elif method == "tools/call":
            params = req.get("params", {})
            name = params.get("name")
            args = params.get("arguments", {})
            if name == "echo":
                reply(id_, result={"content": [{"type": "text", "text": args.get("text", "")}], "isError": False})
            elif name == "env":
                reply(id_, result={"content": [{"type": "text", "text": json.dumps({
                    "has_marker": "AIDOS_TEST_MARKER" in os.environ,
                    "path_present": "PATH" in os.environ,
                    "has_secret": os.environ.get("MCP_SERVER_SECRET") == "resolved-from-vault",
                })}], "isError": False})
            elif name == "hang":
                continue
            elif name == "fail":
                reply(id_, result={"content": [{"type": "text", "text": "boom"}], "isError": True})
            else:
                reply(id_, error={"code": -32601, "message": f"unknown tool '{name}'"})
        else:
            reply(id_, error={"code": -32601, "message": f"unknown method '{method}'"})


if __name__ == "__main__":
    main()
