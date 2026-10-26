# AIDIN spike results (RFC-0105 phase 0)

Phase 0 of [RFC-0105](rfcs/0105-inference-groups.md) asks for numbers and failure behaviour of
llama.cpp RPC layer split before Engine code depends on it. This file collects them. Rows marked
**open** need the owner's machines (real Wi-Fi, office network, a phone).

## Run 1: cloud container, loopback (2026-10-26)

Setup: llama.cpp release `b11901`, built with `-DGGML_RPC=ON` (CPU backend), one Linux container
with 4 vCPUs. Helper `ggml-rpc-server -p 50052 -t 2 -c` and main process on the same machine,
talking over 127.0.0.1. Model Qwen2.5-0.5B-Instruct Q4_K_M (463 MiB, 24 layers). Each side 2 threads.

Note: in this release the helper binary and CMake target are `ggml-rpc-server` (older docs say
`rpc-server`).

| Measure | Single process | Split 1:1 over loopback RPC |
|---|---:|---:|
| Prompt processing, pp128 (tok/s) | 237.5 ± 3.7 | 220.6 ± 12.9 |
| Generation, tg32 (tok/s) | 36.1 ± 1.3 | 26.6 ± 1.1 |

Generation lost ~10 ms per token with one helper even on loopback (no network latency), so the
per-token cost is RPC round trips and graph transfer, not bandwidth. Over Wi-Fi each round trip
adds the link's latency on top. This is the number to re-measure on real Wi-Fi.

### Failure behaviour

| Test | Result |
|---|---|
| Helper killed (`SIGKILL`) during a streaming chat completion | `llama-server` **aborted** (exit 134, `ggml-rpc.cpp:682: Remote RPC server crashed or returned malformed response`) 1.3 s later. The client's SSE stream was cut without an error event (curl exit 18). |
| Helper frozen (`SIGSTOP`, models a peer that silently vanished from Wi-Fi) for 20 s | `llama-server` **hung**: no tokens, no error, no timeout, and `/health` still answered `{"status":"ok"}`. Generation resumed when the helper was continued. |
| Helper weight cache (`-c`) | Works: tensors saved under `$LLAMA_CACHE/rpc/<hash>` on first load. |

Consequences for the implementation (all already required by RFC-0105, now confirmed):

- Split inference must run in a child process: helper loss kills the process that loaded the model.
- The Engine cannot rely on `llama-server` noticing a lost helper or on its `/health`: a silent
  drop hangs it indefinitely. The supervisor must decide from AIDIN heartbeats (SUSPECTED past the
  grace period) and then kill `llama-server` itself.
- Delegated streaming requests need their own `peer_lost` error event; the stream from
  `llama-server` just stops.

## Open (needs the owner's machines)

- Two machines on Wi-Fi and on Ethernet: pp/tg split vs. single, a model that does not fit on one.
- First load vs. cached load time with weights crossing the network.
- Office Wi-Fi: can two devices reach each other at all (client isolation)?
- One Android phone as helper over `adb` (needs an android-arm64 build from CI).

Commands: Appendix A of the project plan, mirrored in RFC-0105 phase 0.
