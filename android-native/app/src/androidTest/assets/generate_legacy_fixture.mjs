// Run with Node 24 from this directory to regenerate the web-format test archive.
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { packSnapshot, unpackSnapshot } from '../../../../../BuildLedger_Web/archive.js';
import { emptyState } from '../../../../../BuildLedger_Web/finance.js';

const id = 'legacy-pdf-1';
const bytes = new TextEncoder().encode('%PDF-1.4\noriginal fixture\n');
const sha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)))
    .map(byte => byte.toString(16).padStart(2, '0')).join('');
const blob = new Blob([bytes], { type: 'application/pdf' });
const state = emptyState();
state.project.name = 'Legacy fixture house';
state.files.push({ id, name: 'Legacy receipt.pdf', type: 'application/pdf', size: bytes.length,
    sha256, importedAt: '2026-01-02T00:00:00.000Z' });
const archive = await packSnapshot(state, [{ id, blob }], 'fixture-password-123');
const recovered = await unpackSnapshot(archive, 'fixture-password-123');
if (recovered.state.files[0].sha256 !== sha256 ||
    new TextDecoder().decode(await recovered.assets[0].blob.arrayBuffer()) !== new TextDecoder().decode(bytes))
    throw Error('Generated legacy fixture failed its web-format roundtrip');
await writeFile(join(dirname(fileURLToPath(import.meta.url)), 'legacy_web_v1.zip'),
    new Uint8Array(await archive.arrayBuffer()));
