#!/usr/bin/env node
// sync-icons.mjs — backfill + validate Glyphew homepage favicons.
//
//   node tools/icons/sync-icons.mjs            download missing/broken icons
//   node tools/icons/sync-icons.mjs --check    validate only, exit 1 if any missing
//
// Parses STATIC_CATALOG out of homepage.js, checks every site's icon against the
// three byte-identical skin asset dirs, and (unless --check) fetches missing ones
// from the Google favicon service, writing identical bytes into all three dirs.
// Zero dependencies — node: builtins only.

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// tools/icons/ -> repo root is two levels up
const repoRoot  = path.resolve(__dirname, '..', '..');
const SKINS = ['assets-gw-signature', 'assets-gw-arcade', 'assets-gw-ice-rink'];
const skinGlyphew = (skin) => path.join(repoRoot, 'app', 'src', 'main', skin, 'glyphew');
const PRIMARY = skinGlyphew(SKINS[0]); // signature is the source of truth for the catalog

const checkMode = process.argv.includes('--check');

// ── Known generic-globe favicon hashes (Google returns these when no real icon) ──
// Add to this set if a new generic variant appears (logged by the script).
const GENERIC_HASHES = new Set([
  // current Google s2 generic globe @ sz=128 — seed on first run (see logs)
]);

// ── 1. Extract STATIC_CATALOG via brace-matched slice + sandboxed eval ──────
function extractCatalog(jsSource) {
  const marker = 'const STATIC_CATALOG = ';
  const mi = jsSource.indexOf(marker);
  if (mi < 0) throw new Error('STATIC_CATALOG not found in homepage.js');
  let i = jsSource.indexOf('[', mi);
  if (i < 0) throw new Error('STATIC_CATALOG opening [ not found');
  const start = i;
  let depth = 0, inStr = false, quote = '', esc = false;
  for (; i < jsSource.length; i++) {
    const ch = jsSource[i];
    if (inStr) {
      if (esc) { esc = false; }
      else if (ch === '\\') { esc = true; }
      else if (ch === quote) { inStr = false; }
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') { inStr = true; quote = ch; continue; }
    if (ch === '[' || ch === '{') depth++;
    else if (ch === ']' || ch === '}') {
      depth--;
      if (depth === 0) { i++; break; }
    }
  }
  const literal = jsSource.slice(start, i);
  // Sandboxed eval of a pure array-of-objects literal — no globals exposed.
  return vm.runInNewContext('(' + literal + ')', Object.create(null), { timeout: 1000 });
}

// ── 2. Real-icon detection (reject 1x1 placeholder PNGs) ────────────────────
const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
function isRealIcon(buf) {
  if (!buf || buf.length < 100) return false;
  if (!buf.subarray(0, 8).equals(PNG_SIG)) return false;
  // IHDR is the first chunk: length(4) type(4)="IHDR" then width(4) height(4)
  if (buf.toString('ascii', 12, 16) !== 'IHDR') return false;
  const w = buf.readUInt32BE(16);
  const h = buf.readUInt32BE(20);
  return w > 2 && h > 2;
}
function fileIsRealIcon(p) {
  try { return isRealIcon(fs.readFileSync(p)); }
  catch { return false; }
}

// ── 3. Collect every (icon -> domain) the catalog needs ─────────────────────
function catalogIcons() {
  // Read from catalog-bundled.json (the new source of truth).
  // Falls back to parsing STATIC_CATALOG from homepage.js for local dev setups
  // that haven't run catalog:bundle yet.
  const bundledPath = path.join(PRIMARY, 'catalog-bundled.json');
  let catalog;
  if (fs.existsSync(bundledPath)) {
    const parsed = JSON.parse(fs.readFileSync(bundledPath, 'utf8'));
    // catalog-bundled.json uses { categories: [{ id, tiles: [{ url, icon }] }] }
    catalog = (parsed.categories || []).map(cat => ({
      pages: [cat.tiles || []],
      _isBundledFormat: true,
    }));
  } else {
    // Legacy fallback
    const js = fs.readFileSync(path.join(PRIMARY, 'homepage.js'), 'utf8');
    catalog = extractCatalog(js);
  }
  const out = new Map(); // icon filename -> domain
  for (const cat of catalog)
    for (const page of cat.pages)
      for (const site of page) {
        if (!site || !site.icon) continue;
        // catalog-bundled.json uses 'url'; homepage.js legacy used 'domain'
        const domain = site.domain ||
            (site.url || '').replace(/^https?:\/\//, '').split('/')[0];
        if (!domain) continue;
        if (!out.has(site.icon)) out.set(site.icon, domain);
      }
  return out;
}

// ── 4. Fetch from Google favicon service ────────────────────────────────────
function faviconUrl(domain) {
  const host = String(domain).split('/')[0].trim();
  return `https://www.google.com/s2/favicons?domain=${encodeURIComponent(host)}&sz=128`;
}
async function fetchIcon(domain) {
  const res = await fetch(faviconUrl(domain), { redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  let buf = Buffer.from(await res.arrayBuffer());
  const ct = (res.headers.get('content-type') || '').split(';')[0].trim();
  if (ct === 'image/jpeg' || ct === 'image/jpg') {
    // Google sometimes returns JPEG; convert to PNG via ImageMagick
    buf = execSync('convert - PNG:-', { input: buf, maxBuffer: 1024 * 1024 });
  }
  const hash = crypto.createHash('sha256').update(buf).digest('hex');
  return { buf, hash };
}

// ── 5. Main ─────────────────────────────────────────────────────────────────
async function main() {
  for (const skin of SKINS) {
    const d = path.join(skinGlyphew(skin), 'icons');
    if (!fs.existsSync(d)) { console.error(`missing icons dir: ${d}`); process.exit(2); }
  }

  const needed = catalogIcons();
  const missing = [];
  for (const [icon, domain] of needed) {
    if (!fileIsRealIcon(path.join(PRIMARY, 'icons', icon))) missing.push({ icon, domain });
  }

  if (checkMode) {
    if (missing.length) {
      console.error(`icon check FAILED — ${missing.length} catalog site(s) lack a real icon:`);
      for (const m of missing) console.error(`  - ${m.icon}  (${m.domain})`);
      console.error('Run: node tools/icons/sync-icons.mjs');
      process.exit(1);
    }
    console.log(`icon check OK — all ${needed.size} catalog icons present and real.`);
    return;
  }

  if (!missing.length) {
    console.log(`nothing to do — all ${needed.size} catalog icons already present.`);
    return;
  }

  console.log(`fetching ${missing.length} missing/broken icon(s)...`);
  const failed = [];
  for (const { icon, domain } of missing) {
    try {
      const { buf, hash } = await fetchIcon(domain);
      if (!isRealIcon(buf)) {
        failed.push({ icon, domain, reason: 'response not a usable PNG' });
        console.warn(`  SKIP ${icon} (${domain}) — not a usable PNG`);
        continue;
      }
      if (GENERIC_HASHES.has(hash)) {
        failed.push({ icon, domain, reason: 'generic globe (no real favicon)' });
        console.warn(`  SKIP ${icon} (${domain}) — Google returned the generic globe`);
        continue;
      }
      for (const skin of SKINS) {
        fs.writeFileSync(path.join(skinGlyphew(skin), 'icons', icon), buf);
      }
      console.log(`  OK   ${icon}  (${domain})  ${buf.length} B  sha256=${hash.slice(0, 12)}`);
    } catch (e) {
      failed.push({ icon, domain, reason: e.message });
      console.warn(`  FAIL ${icon} (${domain}) — ${e.message}`);
    }
  }

  console.log(`\ndone: ${missing.length - failed.length} written, ${failed.length} failed.`);
  if (failed.length) {
    console.log('manual sourcing required for:');
    for (const f of failed) console.log(`  - ${f.icon}  (${f.domain})  [${f.reason}]`);
    process.exitCode = 1; // human notices; downloaded icons are still kept
  }
}

main().catch((e) => { console.error(e); process.exit(2); });
