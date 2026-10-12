#!/usr/bin/env node
// Downloads the SmolVLM-256M-Instruct vision package the Engine's vision backend loads (RFC-0022):
// three uint8 ONNX graphs (~260 MB) plus tokenizer and configs, flat into <dir>.
//
//   node scripts/vision/fetch-smolvlm.mjs <dir>
//   AIDOS_VISION_MODEL_DIR=<dir> gradle :vision:jvmTest :desktopapp:test
//
// Apache-2.0 licensed model from HuggingFaceTB. The *_int8 exports are not fetched: they use signed
// ConvInteger, which ONNX Runtime's CPU provider cannot run.
import { createWriteStream } from 'node:fs';
import { mkdir, rename } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { join } from 'node:path';

const repo = 'HuggingFaceTB/SmolVLM-256M-Instruct';
const files = [
  'config.json', 'processor_config.json', 'generation_config.json', 'tokenizer.json',
  'onnx/vision_encoder_uint8.onnx', 'onnx/embed_tokens_uint8.onnx', 'onnx/decoder_model_merged_uint8.onnx',
];

const dir = process.argv[2];
if (!dir) {
  console.error('usage: node scripts/vision/fetch-smolvlm.mjs <dir>');
  process.exit(2);
}
await mkdir(dir, { recursive: true });

for (const path of files) {
  const name = path.split('/').pop();
  const target = join(dir, name);
  const partial = `${target}.part`;
  console.log(`fetching ${path}`);
  const res = await fetch(`https://huggingface.co/${repo}/resolve/main/${path}`);
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
  await pipeline(Readable.fromWeb(res.body), createWriteStream(partial));
  await rename(partial, target);
}
console.log(`done: ${dir}`);
