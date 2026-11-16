#!/usr/bin/env node
// Downloads the Silero VAD v5 graph (MIT; Hugging Face onnx-community/silero-vad, ~2 MB) as silero_vad.onnx.
//
//   node scripts/stt/fetch-silero-vad.mjs <modelsDir>/silero-vad     shared by every Whisper package in <modelsDir>
//   node scripts/stt/fetch-silero-vad.mjs ~/whisper-tiny             or next to one package
//   AIDOS_VAD_MODEL=<dir>/silero_vad.onnx gradle :stt:jvmTest        (in engine/) runs the real-graph test
import { mkdir, writeFile, stat } from 'node:fs/promises';
import { join } from 'node:path';

const [dir] = process.argv.slice(2);
if (!dir) {
  console.error('usage: fetch-silero-vad.mjs <dir>');
  process.exit(2);
}
const out = join(dir, 'silero_vad.onnx');
try { await stat(out); console.log(`have ${out}`); process.exit(0); } catch { /* download */ }
const res = await fetch('https://huggingface.co/onnx-community/silero-vad/resolve/main/onnx/model.onnx', { redirect: 'follow' });
if (!res.ok) throw new Error(`silero-vad: HTTP ${res.status}`);
await mkdir(dir, { recursive: true });
await writeFile(out, Buffer.from(await res.arrayBuffer()));
console.log(`got  ${out}`);
