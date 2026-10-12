#!/usr/bin/env node
// Downloads a Whisper ONNX package (Hugging Face onnx-community layout) into a directory the Engine's STT
// loader understands, for local runs and for the real-model test:
//
//   node scripts/stt/fetch-whisper.mjs ~/whisper-tiny
//   AIDOS_STT_MODEL_DIR=~/whisper-tiny gradle :stt:jvmTest        (in engine/)
//
// Usage: fetch-whisper.mjs <dir> [repo=onnx-community/whisper-tiny] [precision=quantized]
// precision is the file suffix: "" (fp32), quantized, int8, fp16, q4 ...
import { mkdir, writeFile, stat } from 'node:fs/promises';
import { dirname, join } from 'node:path';

const [dir, repo = 'onnx-community/whisper-tiny', precision = 'quantized'] = process.argv.slice(2);
if (!dir) {
  console.error('usage: fetch-whisper.mjs <dir> [repo] [precision]');
  process.exit(2);
}
const suffix = precision ? `_${precision}` : '';
const files = [
  'tokenizer.json', 'config.json', 'generation_config.json', 'preprocessor_config.json',
  `onnx/encoder_model${suffix}.onnx`, `onnx/decoder_model_merged${suffix}.onnx`,
];

for (const f of files) {
  const out = join(dir, f.replace(/^onnx\//, ''));
  try { await stat(out); console.log(`have ${out}`); continue; } catch { /* download */ }
  const res = await fetch(`https://huggingface.co/${repo}/resolve/main/${f}`, { redirect: 'follow' });
  if (!res.ok) {
    if (f.endsWith('preprocessor_config.json') || f.endsWith('generation_config.json')) { console.warn(`skip ${f} (${res.status})`); continue; }
    throw new Error(`${f}: HTTP ${res.status}`);
  }
  await mkdir(dirname(out), { recursive: true });
  await writeFile(out, Buffer.from(await res.arrayBuffer()));
  console.log(`got  ${out}`);
}
