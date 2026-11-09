#!/usr/bin/env python3
"""
External non-Kotlin MCP server fixture supporting both stdio and Streamable HTTP transports.
Used by McpExternalInteropTest to verify end-to-end interoperability across:
McpServerStore -> McpAdoptionService -> McpOperationAdoptionStore -> McpServerActivator -> LazyMcpTool -> McpLifecycleManager.
"""
import argparse
import http.server
import json
import os
import sys


TOOLS_CATALOG = [
    {
        "name": "add_numbers",
        "description": "Adds two integers a and b and returns the sum.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "a": {"type": "integer"},
                "b": {"type": "integer"},
            },
            "required": ["a", "b"],
        },
    },
    {
        "name": "inspect_auth",
        "description": "Returns whether the expected secret was received via env or header.",
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "unadopted_secret_op",
        "description": "An operation that is never adopted by the test.",
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
]


def handle_rpc_request(req, auth_header=None):
    method = req.get("method")
    id_ = req.get("id")
    if id_ is None:
        return None

    if method == "initialize":
        requested = (req.get("params") or {}).get("protocolVersion", "2024-11-05")
        return {
            "jsonrpc": "2.0",
            "id": id_,
            "result": {
                "protocolVersion": requested,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "python-external-mcp", "version": "1.2.0"},
            },
        }
    elif method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": id_,
            "result": {"tools": TOOLS_CATALOG},
        }
    elif method == "tools/call":
        params = req.get("params") or {}
        name = params.get("name")
        args = params.get("arguments") or {}
        if name == "add_numbers":
            total = int(args.get("a", 0)) + int(args.get("b", 0))
            return {
                "jsonrpc": "2.0",
                "id": id_,
                "result": {
                    "content": [{"type": "text", "text": f"sum={total}"}],
                    "isError": False,
                },
            }
        elif name == "inspect_auth":
            env_secret = os.environ.get("MCP_API_KEY", "")
            return {
                "jsonrpc": "2.0",
                "id": id_,
                "result": {
                    "content": [{
                        "type": "text",
                        "text": json.dumps({
                            "env_secret_present": bool(env_secret),
                            "auth_header": auth_header or "",
                        }),
                    }],
                    "isError": False,
                },
            }
        else:
            return {
                "jsonrpc": "2.0",
                "id": id_,
                "error": {"code": -32601, "message": f"unknown tool '{name}'"},
            }
    else:
        return {
            "jsonrpc": "2.0",
            "id": id_,
            "error": {"code": -32601, "message": f"unknown method '{method}'"},
        }


def run_stdio():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = json.loads(line)
        resp = handle_rpc_request(req)
        if resp is not None:
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()


class McpHttpHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        self.send_response(405)
        self.end_headers()

    def do_DELETE(self):
        self.send_response(405)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8")
        req = json.loads(raw)
        auth_header = self.headers.get("Authorization")
        resp = handle_rpc_request(req, auth_header=auth_header)
        if resp is None:
            self.send_response(202)
            self.end_headers()
            return
        payload = json.dumps(resp).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def run_http():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), McpHttpHandler)
    port = server.server_address[1]
    sys.stdout.write(f"PORT={port}\n")
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["stdio", "http"], required=True)
    args = parser.parse_args()
    if args.mode == "stdio":
        run_stdio()
    else:
        run_http()


if __name__ == "__main__":
    main()
