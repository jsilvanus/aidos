# Trying desktop Aidos with Agentic Messenger

What this checks: the Aidos desktop app serves the Engine and the agent runtime (D48), and Agentic Messenger's AI turns run through it. Nothing here has been run on a real desktop with a loaded model yet; this is the checklist for that first run.

## 1. Aidos desktop

```
cd engine
./gradlew :desktopapp:run        # JDK 17; Windows: gradlew.bat
```

In the window: install a small chat model (Models tab, for example a Qwen 0.5B/1.5B GGUF) and start the Engine. Starting writes `~/.aidos/engine.json` (port 18080, a loopback token, `"agentApi": "/v1/agent"`). Locality is enforced by the Engine (D47, `local_only` by default), so a local model needs no extra configuration.

## 2. Smoke test with curl

```
TOKEN=$(python3 -c "import json,os;print(json.load(open(os.path.expanduser('~/.aidos/engine.json')))['token'])")
curl -s -H "Authorization: Bearer $TOKEN" localhost:18080/v1/models
curl -s -H "Authorization: Bearer $TOKEN" localhost:18080/v1/agent/info
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"model":"<id from /v1/models>","message":"Say hi in five words."}' localhost:18080/v1/agent/turns
```

Expect a `completed` snapshot with a `reply`. Add `"async": true` and poll `GET /v1/agent/runs/<id>?since=0&waitMs=20000` to see the task flow; `GET /v1/agent/events` streams state changes. Full API: [agent-runtime-api.md](agent-runtime-api.md).

## 3. Agentic Messenger

```
cd ../agentic-messenger        # next to the aidos checkout
./gradlew :amp-ui:run          # run_desktop_app.bat on Windows
```

Ask the assistant something in a conversation. It discovers Aidos from `~/.aidos/engine.json` (or `AIDOS_ENGINE_DISCOVERY`). Tools such as `query_chat_messages` and `send_chat_message` run inside Messenger; Aidos only asks for them.

## What to look for

- Stop the Aidos Engine and ask again: Messenger should say Aidos is not running, not fall back to another model.
- A tool-using prompt ("what did Bob say?") should show the tool name as a cue.
- If a model id is wrong or none is installed, the error text should name that, not a connection failure.

Report anything that differs; the gaps known today are listed in D48 (no per-agent authorization, no progress events, runs are in memory).

## Running the Engine tests while the app is open (Windows)

Windows cannot overwrite a loaded `jllama.dll`. The llama.cpp binding extracts it into `%TEMP%`, so running `gradle jvmTest` in `engine` while the Aidos desktop app (or a Gradle daemon from an earlier test run) is alive makes the llama tests fail with `UnsatisfiedLinkError: No native library found` and then `NoClassDefFoundError: de.kherud.llama.LlamaModel`. Stop the app and run `gradle --stop`, delete a stray `jllama.dll` in `%TEMP%` if one remains, then re-run.
