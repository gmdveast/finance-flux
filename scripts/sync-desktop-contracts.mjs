#!/usr/bin/env node
import { readFile, copyFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const fluxRoot = resolve(scriptDir, '..');
const args = process.argv.slice(2);
const checkOnly = args.includes('--check');
const rootIndex = args.indexOf('--desktop-root');
const desktopRoot = rootIndex >= 0 ? resolve(args[rootIndex + 1] || '') : '';

if (!desktopRoot) {
  console.error('Usage: node scripts/sync-desktop-contracts.mjs --desktop-root <Finance-desktop-root> [--check]');
  process.exit(2);
}

const files = [
  ['contracts/mobile-operation.schema.json', 'contracts/mobile-operation.schema.json'],
  ['contracts/mobile-operation-v2.schema.json', 'contracts/mobile-operation-v2.schema.json'],
  ['contracts/mobile-lists.schema.json', 'contracts/mobile-lists.schema.json'],
  ['contracts/mobile-receipt.schema.json', 'contracts/mobile-receipt.schema.json'],
  ['contracts/mobile-reset.schema.json', 'contracts/mobile-reset.schema.json'],
  ['examples/mobile-operation.json', 'examples/mobile-operation.json'],
  ['examples/mobile-automatic-operation.json', 'examples/mobile-automatic-operation.json'],
];

for (const [sourceRelative, targetRelative] of files) {
  const source = resolve(desktopRoot, sourceRelative);
  const target = resolve(fluxRoot, targetRelative);
  const sourceBytes = await readFile(source);
  JSON.parse(sourceBytes.toString('utf8'));
  if (checkOnly) {
    const targetBytes = await readFile(target);
    if (!sourceBytes.equals(targetBytes)) {
      console.error(`Contract copy is stale: ${targetRelative}`);
      process.exitCode = 1;
    }
  } else {
    await copyFile(source, target);
    console.log(`Synced ${targetRelative}`);
  }
}
