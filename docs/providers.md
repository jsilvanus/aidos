# HTTP model providers (Ollama and other OpenAI-compatible servers)

The desktop Engine can serve models that live behind an OpenAI-compatible HTTP API, a local Ollama
being the first use (RFC-0021 providers, `engine/providers`). Provider models appear in `/v1/models`
beside downloaded ones, with ids `<provider>:<model>` (for example `ollama:llama3`).

## Configuration

`providers.json` in the Engine's state directory (next to `approvals.json`). It is read on every
catalog or load call, so editing it needs no restart.

```json
{
  "providers": [
    {
      "id": "ollama",
      "baseUrl": "http://127.0.0.1:11434",
      "treatAsLocal": true,
      "models": [{ "id": "llama3", "contextWindow": 8192 }, { "id": "qwen2.5:7b" }]
    }
  ]
}
```

`apiKey` is optional and is never listed in `/v1/models`.

## Locality (RFC-0104, D47)

A provider's models are **`remote`** placement unless `treatAsLocal` is true **and** `baseUrl`
points at this machine (`localhost`, `127.0.0.0/8`, `::1`). Requests that state no limit are held
to `local_only`, so by default they are refused (HTTP 403) for provider models.

- `treatAsLocal: true` on a loopback URL: the models count as `local`, so local-only requests may
  use them. This is a **development toggle**: Engine cannot attest what a process on a loopback port
  does with the prompt (an Ollama may itself forward to a cloud model).
- `treatAsLocal: true` on any other host is ignored (the model card says `ignored: host is not
  loopback`): the toggle must not become a way to label another machine as this device.
- A client that really wants a remote provider asks for it: `X-Aidos-Locality: any`
  (SDK: `locality = Locality.ANY`), or `"locality": "any"` in an `/v1/agent/turns` request.

Not yet built: a UI switch for the toggle, model discovery from Ollama's `/api/tags` (models are
listed explicitly), streaming from providers (the adapter answers non-streaming and Engine emits it
as one event), and Android (the module is JVM only).
