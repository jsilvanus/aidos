#!/usr/bin/env python3
"""Fake MCP stdio server for StdioMcpClientTest (RFC-0031, M18).

Reads newline-delimited JSON-RPC 2.0 requests from stdin, writes newline-delimited
JSON-RPC 2.0 responses to stdout. Implements just enough of the protocol
(initialize / tools/list / tools/call) to prove StdioMcpClient's real subprocess
transport end to end -- not a real MCP server.

Also implements a debug-only "test/env" method so the Kotlin-side test can prove the
child process's environment was actually scrubbed, not just trust that it was.
"""
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

        if method == "initialize":
            reply(id_, result={"serverInfo": {"name": "fake-mcp-server", "version": "0.0.1"}})
        elif method == "tools/list":
            reply(id_, result={"tools": [
                {
                    "name": "echo",
                    "description": "echoes its input back",
                    "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
                }
            ]})
        elif method == "tools/call":
            params = req.get("params", {})
            name = params.get("name")
            args = params.get("arguments", {})
            if name == "echo":
                reply(id_, result={"content": [{"type": "text", "text": args.get("text", "")}], "isError": False})
            elif name == "fail":
                reply(id_, result={"content": [{"type": "text", "text": "boom"}], "isError": True})
            else:
                reply(id_, error={"code": -32601, "message": f"unknown tool '{name}'"})
        elif method == "test/env":
            reply(id_, result={
                "has_marker": "AIDOS_TEST_MARKER" in os.environ,
                "path_present": "PATH" in os.environ,
                "has_secret": os.environ.get("MCP_SERVER_SECRET") == "resolved-from-vault",
            })
        elif method == "test/hang":
            # Never replies -- exercises the client's request timeout.
            continue
        else:
            reply(id_, error={"code": -32601, "message": f"unknown method '{method}'"})


if __name__ == "__main__":
    main()
