'use strict';

// ── Fit-to-viewport scaling ─────────────────────────────────────────────────
// #app is a fixed 1280×720 design canvas. Scale it to fill whatever physical
// window size Wolvic gives us (0.5x / 1x / 2x / 3x mode), preserving aspect
// ratio with letter-boxing.
(function fitToViewport() {
  function apply() {
    var scale = Math.min(window.innerWidth / 1280, window.innerHeight / 720);
    document.documentElement.style.setProperty('--gw-fit', scale);
    // Centre the scaled canvas so it's not stuck to the top-left corner.
    var app = document.getElementById('app');
    if (app) {
      app.style.marginLeft = Math.max(0, (window.innerWidth  - 1280 * scale) / 2) + 'px';
      app.style.marginTop  = Math.max(0, (window.innerHeight - 720  * scale) / 2) + 'px';
    }
  }
  window.addEventListener('resize', apply);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', apply);
  } else {
    apply();
  }
}());

// ── Remote catalog ─────────────────────────────────────────────────────────
// Loaded synchronously from HomeBridge.getCatalog() at startup.
// HomeBridge returns the remote-updated catalog if one has been fetched and
// validated; otherwise falls back to the bundled catalog-bundled.json asset.
// The old STATIC_CATALOG constant has been removed — catalog/source.json in
// the glyphew repo is now the single source of truth.
var REMOTE_CATALOG = (function() {
  try {
    var raw = (typeof gwHome !== 'undefined') ? gwHome.getCatalog() : null;
    if (!raw) return [];
    var parsed = JSON.parse(raw);
    var cats = (parsed && Array.isArray(parsed.categories)) ? parsed.categories : [];
    // Normalise to the shape homepage.js expects: { id, title, icon, pages[] }
    return cats.map(function(cat) {
      return {
        id:    cat.id    || '',
        title: cat.label || cat.title || '',
        icon:  '◎',
        pages: [(cat.tiles || []).map(function(t) {
          return {
            name:   t.label  || t.name  || '',
            domain: (t.url || '').replace(/^https?:\/\//, '').split('/')[0],
            url:    t.url    || '',
            icon:   t.icon   || '',
            color:  t.color  || '#333333',
            letter: t.letter || (t.label || '?').charAt(0).toUpperCase(),
          };
        })],
      };
    });
  } catch (e) {
    return [];
  }
}());

// Legacy alias so existing multi-page categories (tools has 2 pages) still work.
// The remote catalog flattens each category to a single page of tiles. For the
// tools category the second page (VR news) is included in the tile list but
// pagination into multiple pages of 8 is handled by the normalisation above
// once HomeBridge returns the full tile list.

// ── STATIC_CATALOG removed — catalog served from HomeBridge.getCatalog() ──

// Tips shown during logo hub slide (cycles each logo appearance)
const HUB_TIPS = [
  'HOLD GRIP · FLICK · RELEASE',
  'PUSH UP / DOWN = CATEGORY',
  'PUSH LEFT / RIGHT = PAGE',
  'GRIP + FLICK = COMBO',
];

// Hub slide deck: logo + all 13 assigned combos (§4.3) + long-press.
// Labels: action name first, then arrow sequence (per user spec).
const HUB_SLIDES_4DIR = [
  { type: 'logo',                                                                            duration: 3000 },
  { type: 'combo', path: [2],       name: 'SCROLL UP',        arrows: ['↑'],               duration: 2500 },
  { type: 'combo', path: [8],       name: 'SCROLL DOWN',      arrows: ['↓'],               duration: 2500 },
  { type: 'combo', path: [4],       name: 'SCROLL LEFT',      arrows: ['←'],               duration: 2500 },
  { type: 'combo', path: [6],       name: 'SCROLL RIGHT',     arrows: ['→'],               duration: 2500 },
  { type: 'combo', path: [2,2],     name: 'SCROLL TO TOP',    arrows: ['↑','↑'],           duration: 2500 },
  { type: 'combo', path: [8,8],     name: 'SCROLL TO BOTTOM', arrows: ['↓','↓'],           duration: 2500 },
  { type: 'combo', path: [4,4],     name: 'BACK',             arrows: ['←','←'],           duration: 2500 },
  { type: 'combo', path: [6,6],     name: 'FORWARD',          arrows: ['→','→'],           duration: 2500 },
  { type: 'combo', path: [2,8],     name: 'REFRESH',          arrows: ['↑','↓'],           duration: 2500 },
  { type: 'combo', path: [4,6],     name: 'STOP',             arrows: ['←','→'],           duration: 2500 },
  { type: 'combo', path: [8,2],     name: 'FIND IN PAGE',     arrows: ['↓','↑'],           duration: 2500 },
  { type: 'combo', path: [2,2,2],   name: 'OPEN HISTORY',     arrows: ['↑','↑','↑'],       duration: 2500 },
  { type: 'combo', path: [8,8,8],   name: 'ADD BOOKMARK',     arrows: ['↓','↓','↓'],       duration: 2500 },
  { type: 'combo', path: [4,4,4],   name: 'CLOSE WINDOW',     arrows: ['←','←','←'],       duration: 2500 },
  { type: 'combo', path: [6,6,6],   name: 'NEW WINDOW',       arrows: ['→','→','→'],       duration: 2500 },
  { type: 'combo', path: [6,6,6,6], name: 'DUPLICATE WINDOW', arrows: ['→','→','→','→'],   duration: 2500 },
  { type: 'combo', path: [2,6],     name: 'NEXT WINDOW',      arrows: ['↑','→'],           duration: 2500 },
  { type: 'combo', path: [2,4],     name: 'PREV WINDOW',      arrows: ['↑','←'],           duration: 2500 },
  { type: 'combo', path: [6,8],     name: 'URL BAR',          arrows: ['→','↓'],           duration: 2500 },
  { type: 'combo', path: [4,8],     name: 'OPEN BOOKMARKS',   arrows: ['←','↓'],           duration: 2500 },
  { type: 'combo', path: [2,6,8],   name: 'PRIVATE MODE',     arrows: ['↑','→','↓'],       duration: 2500 },
  { type: 'longpress',              name: 'SETTINGS',          arrows: ['⊙'],               duration: 2500 },
];

// 8-dir mode replaces the mode-specific combos with diagonal-primary paths.
const HUB_SLIDES_8DIR = [
  { type: 'logo',                                                                               duration: 3000 },
  { type: 'combo', path: [2],         name: 'SCROLL UP',        arrows: ['↑'],                duration: 2500 },
  { type: 'combo', path: [8],         name: 'SCROLL DOWN',      arrows: ['↓'],                duration: 2500 },
  { type: 'combo', path: [4],         name: 'SCROLL LEFT',      arrows: ['←'],                duration: 2500 },
  { type: 'combo', path: [6],         name: 'SCROLL RIGHT',     arrows: ['→'],                duration: 2500 },
  { type: 'combo', path: [2,2],       name: 'SCROLL TO TOP',    arrows: ['↑','↑'],            duration: 2500 },
  { type: 'combo', path: [8,8],       name: 'SCROLL TO BOTTOM', arrows: ['↓','↓'],            duration: 2500 },
  { type: 'combo', path: [4,4],       name: 'BACK',             arrows: ['←','←'],            duration: 2500 },
  { type: 'combo', path: [6,6],       name: 'FORWARD',          arrows: ['→','→'],            duration: 2500 },
  { type: 'combo', path: [2,8],       name: 'REFRESH',          arrows: ['↑','↓'],            duration: 2500 },
  { type: 'combo', path: [4,6],       name: 'STOP',             arrows: ['←','→'],            duration: 2500 },
  { type: 'combo', path: [8,2],       name: 'FIND IN PAGE',     arrows: ['↓','↑'],            duration: 2500 },
  { type: 'combo', path: [2,2,2],     name: 'OPEN HISTORY',     arrows: ['↑','↑','↑'],        duration: 2500 },
  { type: 'combo', path: [8,8,8],     name: 'ADD BOOKMARK',     arrows: ['↓','↓','↓'],        duration: 2500 },
  { type: 'combo', path: [1],         name: 'CLOSE WINDOW',     arrows: ['↖'],                duration: 2500 },
  { type: 'combo', path: [3],         name: 'NEW WINDOW',       arrows: ['↗'],                duration: 2500 },
  { type: 'combo', path: [3,3],       name: 'DUPLICATE WINDOW', arrows: ['↗','↗'],            duration: 2500 },
  { type: 'combo', path: [2,3,6],     name: 'NEXT WINDOW',      arrows: ['↑','↗','→'],        duration: 2500 },
  { type: 'combo', path: [2,1,4],     name: 'PREV WINDOW',      arrows: ['↑','↖','←'],        duration: 2500 },
  { type: 'combo', path: [9],         name: 'URL BAR',          arrows: ['↘'],                duration: 2500 },
  { type: 'combo', path: [7],         name: 'OPEN BOOKMARKS',   arrows: ['↙'],                duration: 2500 },
  { type: 'combo', path: [2,3,6,9,8], name: 'PRIVATE MODE',     arrows: ['↑','↗','→','↘','↓'],duration: 2500 },
  { type: 'longpress',                name: 'SETTINGS',          arrows: ['⊙'],                duration: 2500 },
];

// Active slide set — reassigned when mode is read from bridge prefs.
var HUB_SLIDES = HUB_SLIDES_4DIR;

// ms into the animation when each arrow peaks — matched to CSS @keyframes percentages.
var PATH_STEP_TIMES = {
  '2':[700],'8':[700],'4':[700],'6':[700],
  '2-2':[400,1100],'8-8':[400,1100],'4-4':[400,1100],'6-6':[400,1100],
  '2-8':[440,1320],'4-6':[440,1320],'8-2':[440,1320],
  '2-2-2':[240,700,1160],'8-8-8':[240,700,1160],'4-4-4':[240,700,1160],'6-6-6':[240,700,1160],
  '6-6-6-6':[250,700,1150,1600],
  '2-6':[300,1000],'2-4':[300,1000],'6-8':[300,1000],'4-8':[300,1000],
  '2-6-8':[240,760,1280],
  // 8-dir diagonals (single push: same timing as cardinal)
  '1':[700],'3':[700],'7':[700],'9':[700],
  '3-3':[400,1100],
  // 8-dir multi-step (reuse NE/NW/NES timing)
  '2-3-6':[300,660,1000],'2-1-4':[300,660,1000],
  '2-3-6-9-8':[240,500,760,1020,1280],
};

// ── Grid slot mapping ──────────────────────────────────────────────────────
// 3×3 grid positions 0-8. Center = 4. Outer slots: 0,1,2,3,5,6,7,8 → catalog idx 0-7.
const OUTER_TO_GRID = [0, 1, 2, 3, 5, 6, 7, 8];
const GRID_TO_OUTER = { 0:0, 1:1, 2:2, 3:3, 5:4, 6:5, 7:6, 8:7 };

// Hub combo disc geometry — 116×116 hub, ring at radius 38 px, center (58,58).
// Node angles: 2=270° (up), 8=90° (down), 4=180° (left), 6=0° (right).
var DIR_NODES  = { 2:{x:58,y:20}, 8:{x:58,y:96}, 4:{x:20,y:58}, 6:{x:96,y:58} };
var DIAG_NODES = [{x:85,y:31},{x:85,y:85},{x:31,y:85},{x:31,y:31}];
var CAP_CLASS  = { 2:'to-n', 8:'to-s', 4:'to-w', 6:'to-e' };

// ── State ──────────────────────────────────────────────────────────────────
const state = {
  catalog: REMOTE_CATALOG,
  rowIndex: 0,
  pageIndex: 0,
  focusIdx: 0,
  hintDismissed: false,
  is8Dir: false,
};

// Hub slide state (presentation only — not serialized)
var _hubSlideIdx   = 0;
var _hubTipIdx     = 0;
var _hubSlideTimer = null;

// Position saved before site navigation so the homepage restores its row/page on back.
// -1 means no saved position; getBookmarkCategories reads and clears these.
var _savedRow  = -1;
var _savedPage = 0;
var _slideQueue    = [];  // shuffled index queue for random combo order
var _stepTimers    = [];  // setTimeout IDs for per-arrow stripe lighting

function pickNextSlide() {
  if (_slideQueue.length === 0) {
    var comboIndices = [];
    for (var i = 1; i < HUB_SLIDES.length; i++) comboIndices.push(i);
    // Fisher-Yates shuffle
    for (var j = comboIndices.length - 1; j > 0; j--) {
      var k = Math.floor(Math.random() * (j + 1));
      var tmp = comboIndices[j]; comboIndices[j] = comboIndices[k]; comboIndices[k] = tmp;
    }
    _slideQueue = [0].concat(comboIndices); // logo leads each full cycle
  }
  return _slideQueue.shift();
}

// ── DOM refs ───────────────────────────────────────────────────────────────
const $ = (id) => document.getElementById(id);
const headerKind   = $('header-kind');
const headerTitle  = $('header-title');
const headerDots   = $('header-dots');
const stripeTip    = $('stripe-tip');
const rowRailEl    = $('row-rail');
const chevronN     = $('chevron-n');
const chevronS     = $('chevron-s');
const gridCanvas   = $('grid-canvas');
const ghostPrev    = $('ghost-prev');
const ghostNext    = $('ghost-next');

// ── Render ─────────────────────────────────────────────────────────────────
function currentRow() {
  return state.catalog[state.rowIndex] || state.catalog[0];
}

function currentPage() {
  var row = currentRow();
  return (row.pages[state.pageIndex] || row.pages[0]) || [];
}

function render(opts) {
  var row = currentRow();
  opts = opts || {};

  headerKind.textContent  = row.kind === 'user' ? 'BOOKMARKS' : 'HOME';
  headerTitle.textContent = row.title;
  renderDots(row.pages.length, state.pageIndex);

  renderRail();
  renderGhosts();

  var manyRows = state.catalog.length > 1;
  chevronN.classList.toggle('hidden', !manyRows);
  chevronS.classList.toggle('hidden', !manyRows);

  if (opts.animate) {
    gridCanvas.classList.add('fading');
    setTimeout(function() {
      renderGrid();
      gridCanvas.classList.remove('fading');
      gridCanvas.classList.add('pulsing');
      setTimeout(function() { gridCanvas.classList.remove('pulsing'); }, 400);
    }, 110);
  } else {
    renderGrid();
  }
}

function renderDots(count, current) {
  headerDots.innerHTML = '';
  for (var i = 0; i < count; i++) {
    var dot = document.createElement('div');
    dot.className = 'page-dot' + (i === current ? ' on' : '');
    headerDots.appendChild(dot);
  }
}

function renderRail() {
  rowRailEl.innerHTML = '';
  state.catalog.forEach(function(row, i) {
    var item = document.createElement('div');
    item.className = 'rail-item' + (i === state.rowIndex ? ' on' : '');
    item.setAttribute('aria-label', row.title);

    var iconEl = document.createElement('div');
    iconEl.className = 'rail-icon';
    iconEl.textContent = row.icon || '◉';

    var label = document.createElement('div');
    label.className = 'rail-label';
    label.textContent = row.title;

    item.appendChild(iconEl);
    item.appendChild(label);
    item.addEventListener('pointerdown', function() {
      changeRow(i - state.rowIndex);
    });
    rowRailEl.appendChild(item);
  });
}

function renderGrid() {
  gridCanvas.innerHTML = '';
  var sites = currentPage();

  OUTER_TO_GRID.forEach(function(gridPos, outerIdx) {
    var slot = document.createElement('div');
    slot.className = 'cell-slot';

    if (outerIdx === 3) {
      gridCanvas.appendChild(slot);
      slot.appendChild(makeSiteCell(sites[outerIdx], outerIdx));

      var centerSlot = document.createElement('div');
      centerSlot.className = 'cell-slot center-slot';
      centerSlot.appendChild(makeCenterHub());
      gridCanvas.appendChild(centerSlot);
      return;
    }

    slot.appendChild(makeSiteCell(sites[outerIdx], outerIdx));
    gridCanvas.appendChild(slot);
  });

  applyFocus(state.focusIdx);
}

// Resolve the icon src for any site tile — catalog or user bookmark.
//   1. Bundled base64 from __gwIcons (catalog sites — instant, zero network)
//   2. Live Google favicon URL (bookmark/runtime sites — browser-cached HTTP GET)
//   3. Empty string — triggers the error-listener letter-glyph fallback
function resolveIconSrc(site) {
  if (site && site.icon && window.__gwIcons && window.__gwIcons[site.icon]) {
    return window.__gwIcons[site.icon];
  }
  if (site && site.domain) {
    var host = String(site.domain).split('/')[0];
    return 'https://www.google.com/s2/favicons?domain=' + encodeURIComponent(host) + '&sz=128';
  }
  return '';
}

function makeSiteCell(site, outerIdx) {
  if (!site) {
    var empty = document.createElement('div');
    empty.className = 'cell cell-empty';
    empty.appendChild(document.createElement('div')).className = 'cell-tile';
    empty.appendChild(document.createElement('div')).className = 'cell-label';
    return empty;
  }

  var cell = document.createElement('div');
  cell.className = 'cell';
  cell.dataset.outerIdx = outerIdx;

  var tile = document.createElement('div');
  tile.className = 'cell-tile';
  if (site.color) tile.style.setProperty('--brand', site.color);

  var img = document.createElement('img');
  img.className = 'cell-favicon';
  img.alt = site.name;

  img.addEventListener('error', function() {
    img.style.display = 'none';
    var glyph = document.createElement('div');
    glyph.className = 'cell-glyph';
    glyph.textContent = site.letter || site.name[0];
    tile.insertBefore(glyph, tile.firstChild);
  });
  img.src = resolveIconSrc(site);
  tile.appendChild(img);
  cell.appendChild(tile);

  var label = document.createElement('div');
  label.className = 'cell-label';
  label.textContent = site.name;
  cell.appendChild(label);

  cell.addEventListener('pointerenter', function() { setFocus(outerIdx); });
  cell.addEventListener('pointerdown',  function() { setFocus(outerIdx); });
  cell.addEventListener('click',        function() { activateOuter(outerIdx); });

  return cell;
}

// ── Ghost peek columns ─────────────────────────────────────────────────────
function makeGhostCell(site) {
  var cell = document.createElement('div');
  cell.className = 'ghost-cell';
  var tile = document.createElement('div');
  tile.className = 'ghost-tile';
  if (site) {
    if (site.color) tile.style.setProperty('--brand', site.color);
    var img = document.createElement('img');
    img.className = 'ghost-favicon';
    img.alt = site.name || '';
    img.addEventListener('error', function() {
      img.style.display = 'none';
      var glyph = document.createElement('div');
      glyph.className = 'ghost-glyph';
      glyph.textContent = site.letter || (site.name ? site.name[0] : '?');
      tile.insertBefore(glyph, tile.firstChild);
    });
    img.src = resolveIconSrc(site);
    tile.appendChild(img);
  }
  cell.appendChild(tile);
  return cell;
}

function renderGhosts() {
  if (!ghostPrev || !ghostNext) return;
  ghostPrev.innerHTML = '';
  ghostNext.innerHTML = '';
  var row = currentRow();
  var pages = row.pages.length;
  if (pages <= 1) return;
  var prevIdx = ((state.pageIndex - 1) % pages + pages) % pages;
  var nextIdx = (state.pageIndex + 1) % pages;
  var prevPage = row.pages[prevIdx] || [];
  var nextPage = row.pages[nextIdx] || [];
  // Right column of prev page (grid col 2 = outer indices 2, 4, 7)
  [2, 4, 7].forEach(function(oi) {
    ghostPrev.appendChild(makeGhostCell(prevPage[oi]));
  });
  // Left column of next page (grid col 0 = outer indices 0, 3, 5)
  [0, 3, 5].forEach(function(oi) {
    ghostNext.appendChild(makeGhostCell(nextPage[oi]));
  });
}

// ── Center hub (PowerPoint slide: logo → combos) ───────────────────────────
function makeCenterHub() {
  var hub = document.createElement('div');
  hub.className = 'hub';
  hub.id = 'hub-center';

  var inner = document.createElement('div');
  inner.id = 'hub-inner';
  inner.className = 'hub-inner';
  hub.appendChild(inner);

  populateHubInner(inner);
  return hub;
}

function buildLogoHTML() {
  return '<svg class="hub-logo-svg" viewBox="0 0 604 677" xmlns="http://www.w3.org/2000/svg">' +
         '<use href="#gw-glyph"/>' +
         '</svg>';
}

// Maps a path array (e.g. [2,2]) to the CSS animation class name
function pathToCapClass(path) {
  var key = path.join('-');
  var MAP = {
    '2':     'to-n',  '8':     'to-s',  '4':     'to-w',  '6':     'to-e',
    '2-2':     'to-n2', '8-8':     'to-s2', '4-4':     'to-w2', '6-6':     'to-e2',
    '2-8':     'to-ns', '4-6':     'to-we', '8-2':     'to-sn',
    '2-2-2':   'to-n3', '8-8-8':   'to-s3', '4-4-4':   'to-w3', '6-6-6':   'to-e3',
    '6-6-6-6': 'to-e4',
    '2-6':     'to-ne', '2-4':     'to-nw', '6-8':     'to-es', '4-8':     'to-ws',
    '2-6-8':   'to-nes',
    // 8-dir diagonal single pushes
    '1': 'to-nw1', '3': 'to-ne1', '7': 'to-sw1', '9': 'to-se1',
    '3-3': 'to-ne1-2',
    // 8-dir multi-step — arc paths coincide with NE/NW/NES keyframes
    '2-3-6': 'to-ne', '2-1-4': 'to-nw', '2-3-6-9-8': 'to-nes',
  };
  return MAP[key] || 'to-n';
}

// SVG arc helpers — mirror ComboPathView.java's arc + arrow drawing.
// All 8 node angles: 0°=right CW (same convention as ComboPathView.java).
var _NODE_ANG = { 1:225, 2:270, 3:315, 4:180, 6:0, 7:135, 8:90, 9:45 };

function _discNodePos(node) {
  var rad = _NODE_ANG[node] * Math.PI / 180;
  return { x: 58 + 38 * Math.cos(rad), y: 58 + 38 * Math.sin(rad) };
}

function _discArrow(tipX, tipY, tanX, tanY, len, half, color) {
  var perpX = -tanY, perpY = tanX;
  var bx = tipX - tanX * len, by = tipY - tanY * len;
  return '<path d="M ' + tipX.toFixed(2) + ' ' + tipY.toFixed(2) +
         ' L ' + (bx + perpX * half).toFixed(2) + ' ' + (by + perpY * half).toFixed(2) +
         ' L ' + bx.toFixed(2) + ' ' + by.toFixed(2) +
         ' Z" fill="' + color + '"/>';
}

function buildComboSlideHTML(path, is8Dir) {
  var cx = 58, cy = 58, r = 38;
  var accent = '#FDDE0A';
  var sw = 2.5;                        // stroke width
  var arrowLen = sw * 4.2, arrowHalf = sw * 2.2;
  var loopR = 10;                      // multi-hit loop radius
  var parts = [];
  // Adjacent nodes are separated by one ring step; use arc. Non-adjacent → straight chord.
  var ringStep = is8Dir ? 45 : 90;

  parts.push('<svg class="combo-disc-svg" viewBox="0 0 116 116" xmlns="http://www.w3.org/2000/svg">');
  parts.push('<circle cx="58" cy="58" r="52" fill="#090d38"/>');
  parts.push('<circle cx="58" cy="58" r="38" fill="none" stroke="#1c2265" stroke-width="1.5"/>');

  // Dim background node dots — all 8 equal in 8-dir (distinct octagon), 4 + ghost diagonals in 4-dir
  if (is8Dir) {
    // Draw thin octagon connecting all 8 nodes so the 8-dir layout is unambiguous
    var octPts = [1, 2, 3, 6, 9, 8, 7, 4].map(function(n) {
      var p = _discNodePos(n);
      return p.x.toFixed(2) + ',' + p.y.toFixed(2);
    }).join(' ');
    parts.push('<polygon points="' + octPts + '" fill="none" stroke="#1e2570" stroke-width="1" opacity="0.7"/>');
    [1, 2, 3, 4, 6, 7, 8, 9].forEach(function(n) {
      var p = _discNodePos(n);
      parts.push('<circle cx="' + p.x.toFixed(2) + '" cy="' + p.y.toFixed(2) +
                 '" r="3" fill="#2a3280"/>');
    });
  } else {
    DIAG_NODES.forEach(function(n) {
      parts.push('<circle cx="' + n.x + '" cy="' + n.y + '" r="1.8" fill="#1a2060"/>');
    });
    [2, 8, 4, 6].forEach(function(d) {
      var n = DIR_NODES[d];
      parts.push('<circle cx="' + n.x + '" cy="' + n.y + '" r="3" fill="#252c75"/>');
    });
  }

  // Deduplicate consecutive nodes for segment drawing
  var deduped = [];
  path.forEach(function(n) {
    if (!deduped.length || deduped[deduped.length - 1] !== n) deduped.push(n);
  });

  // Max consecutive repeat count per node (for multi-hit loops)
  var multiHit = {};
  var i = 0;
  while (i < path.length) {
    var n = path[i], j = i + 1;
    while (j < path.length && path[j] === n) j++;
    var cnt = j - i;
    if (cnt > (multiHit[n] || 0)) multiHit[n] = cnt;
    i = j;
  }

  // Segments: arc if adjacent (one ring step), straight chord otherwise
  for (var k = 0; k + 1 < deduped.length; k++) {
    var from = deduped[k], to = deduped[k + 1];
    var startDeg = _NODE_ANG[from], endDeg = _NODE_ANG[to];
    var diff = ((endDeg - startDeg) % 360 + 360) % 360;
    var sweepDeg = diff <= 180 ? diff : diff - 360;
    var absDiff = Math.abs(sweepDeg);
    var p1 = _discNodePos(from), p2 = _discNodePos(to);

    if (absDiff <= ringStep + 0.5) {
      // Adjacent nodes — arc along the ring
      var largeArc = absDiff > 180 ? 1 : 0;
      var sweepFlag = sweepDeg >= 0 ? 1 : 0;
      parts.push('<path d="M ' + p1.x.toFixed(2) + ' ' + p1.y.toFixed(2) +
                 ' A ' + r + ' ' + r + ' 0 ' + largeArc + ' ' + sweepFlag +
                 ' ' + p2.x.toFixed(2) + ' ' + p2.y.toFixed(2) +
                 '" fill="none" stroke="' + accent + '" stroke-width="' + sw + '" stroke-linecap="round"/>');
      // Arrowhead at 0.618 of arc, tangent to travel direction
      var midDeg = startDeg + sweepDeg * 0.618;
      var midRad = midDeg * Math.PI / 180;
      var aTipX = cx + r * Math.cos(midRad), aTipY = cy + r * Math.sin(midRad);
      var tanX = sweepDeg >= 0 ? -Math.sin(midRad) :  Math.sin(midRad);
      var tanY = sweepDeg >= 0 ?  Math.cos(midRad) : -Math.cos(midRad);
      parts.push(_discArrow(aTipX, aTipY, tanX, tanY, arrowLen, arrowHalf, accent));
    } else {
      // Non-adjacent nodes — straight chord
      var dx = p2.x - p1.x, dy = p2.y - p1.y;
      var dlen = Math.sqrt(dx * dx + dy * dy);
      parts.push('<line x1="' + p1.x.toFixed(2) + '" y1="' + p1.y.toFixed(2) +
                 '" x2="' + p2.x.toFixed(2) + '" y2="' + p2.y.toFixed(2) +
                 '" stroke="' + accent + '" stroke-width="' + sw + '" stroke-linecap="round"/>');
      var tipX = p1.x + dx * 0.618, tipY = p1.y + dy * 0.618;
      parts.push(_discArrow(tipX, tipY, dx / dlen, dy / dlen, arrowLen, arrowHalf, accent));
    }
  }

  // Multi-hit loop arcs + N× badge — include diagonal nodes in 8-dir mode
  var loopNodes = is8Dir ? [1, 2, 3, 4, 6, 7, 8, 9] : [2, 4, 6, 8];
  loopNodes.forEach(function(node) {
    var count = multiHit[node] || 0;
    if (count < 2) return;
    var nodeRad = _NODE_ANG[node] * Math.PI / 180;
    var nx = cx + r * Math.cos(nodeRad), ny = cy + r * Math.sin(nodeRad);
    var inwardDeg = (_NODE_ANG[node] + 180) % 360;
    var arcStartDeg = (inwardDeg + 60) % 360;
    var arcEndDeg   = (arcStartDeg + 240) % 360;
    var sRad = arcStartDeg * Math.PI / 180, eRad = arcEndDeg * Math.PI / 180;
    var lsx = nx + loopR * Math.cos(sRad), lsy = ny + loopR * Math.sin(sRad);
    var lex = nx + loopR * Math.cos(eRad), ley = ny + loopR * Math.sin(eRad);
    // large-arc=1, sweep=1 (CW, 240°)
    parts.push('<path d="M ' + lsx.toFixed(2) + ' ' + lsy.toFixed(2) +
               ' A ' + loopR + ' ' + loopR + ' 0 1 1 ' + lex.toFixed(2) + ' ' + ley.toFixed(2) +
               '" fill="none" stroke="' + accent + '" stroke-width="' + sw + '" stroke-linecap="round"/>');
    // Arrowhead at arc end, CW tangent
    var lTanX = -Math.sin(eRad), lTanY = Math.cos(eRad);
    parts.push(_discArrow(lex, ley, lTanX, lTanY, arrowLen * 0.75, arrowHalf * 0.75, accent));
    // N× badge inward from node
    var badgeRad = (((_NODE_ANG[node] + 180) % 360)) * Math.PI / 180;
    var bx = nx + Math.cos(badgeRad) * loopR * 1.5, by = ny + Math.sin(badgeRad) * loopR * 1.5;
    parts.push('<text x="' + bx.toFixed(1) + '" y="' + (by + 3.5).toFixed(1) +
               '" text-anchor="middle" font-size="9.5" font-weight="700" fill="' + accent + '">' + count + '×</text>');
  });

  // Node dots: first = filled yellow, subsequent = hollow yellow ring
  var drawn = {};
  var isFirst = true;
  deduped.forEach(function(node) {
    if (drawn[node]) return;
    drawn[node] = true;
    var p = _discNodePos(node);
    if (isFirst) {
      parts.push('<circle cx="' + p.x.toFixed(2) + '" cy="' + p.y.toFixed(2) +
                 '" r="5.5" fill="' + accent + '"/>');
      isFirst = false;
    } else {
      parts.push('<circle cx="' + p.x.toFixed(2) + '" cy="' + p.y.toFixed(2) +
                 '" r="4.5" fill="none" stroke="' + accent + '" stroke-width="1.8"/>');
    }
  });

  parts.push('</svg>');
  parts.push('<div class="hub-cap ' + pathToCapClass(path) + '"></div>');
  return parts.join('');
}

function buildLongPressHTML() {
  var parts = [];

  parts.push('<svg class="combo-disc-svg" viewBox="0 0 116 116" xmlns="http://www.w3.org/2000/svg">');
  parts.push('<circle cx="58" cy="58" r="52" fill="#090d38"/>');
  parts.push('<circle cx="58" cy="58" r="38" fill="none" stroke="#1c2265" stroke-width="1.5"/>');
  // All node dots dim
  DIAG_NODES.forEach(function(n) {
    parts.push('<circle cx="' + n.x + '" cy="' + n.y + '" r="2.5" fill="#1a2060"/>');
  });
  [2, 8, 4, 6].forEach(function(d) {
    var n = DIR_NODES[d];
    parts.push('<circle cx="' + n.x + '" cy="' + n.y + '" r="4" fill="#252c75"/>');
  });
  // Pulsing center: expanding ring + glowing fill
  parts.push('<circle class="lp-ring" cx="58" cy="58" r="14" fill="none" stroke="#FDDE0A" stroke-width="1.5" opacity="0.5"/>');
  parts.push('<circle class="lp-fill" cx="58" cy="58" r="8" fill="#FDDE0A"/>');
  parts.push('</svg>');
  // Cap stays at center (lp-cap = glow only, no directional animation)
  parts.push('<div class="hub-cap lp-cap"></div>');

  return parts.join('');
}

function populateHubInner(inner) {
  var slide = HUB_SLIDES[_hubSlideIdx];
  if (slide.type === 'logo') {
    inner.innerHTML = buildLogoHTML();
  } else if (slide.type === 'longpress') {
    inner.innerHTML = buildLongPressHTML();
  } else {
    inner.innerHTML = buildComboSlideHTML(slide.path, state.is8Dir);
  }
}

function nextHubSlide() {
  _hubSlideIdx = pickNextSlide();
  var slide = HUB_SLIDES[_hubSlideIdx];

  var inner = document.getElementById('hub-inner');
  if (inner) {
    inner.style.opacity = '0';
    setTimeout(function() {
      populateHubInner(inner);
      inner.style.opacity = '1';
    }, 200);
  }

  updateStripeTip(slide);
  scheduleNextSlide();
}

function scheduleNextSlide() {
  clearTimeout(_hubSlideTimer);
  _hubSlideTimer = setTimeout(nextHubSlide, HUB_SLIDES[_hubSlideIdx].duration);
}

function updateStripeTip(slide) {
  _stepTimers.forEach(clearTimeout);
  _stepTimers = [];

  stripeTip.style.opacity = '0';
  setTimeout(function() {
    if (slide.type === 'logo') {
      stripeTip.textContent = HUB_TIPS[_hubTipIdx];
      _hubTipIdx = (_hubTipIdx + 1) % HUB_TIPS.length;
      stripeTip.removeAttribute('data-combo');
      stripeTip.style.opacity = '0.82';
      return;
    }

    // Build: action name + individual arrow spans (initially dim)
    var arrows = slide.arrows || [];
    var pathKey = slide.path ? slide.path.join('-') : '';
    var stepTimes = PATH_STEP_TIMES[pathKey] ||
                    arrows.map(function(_, i) { return (i + 1) * 600; });

    var html = '<span class="s-name">' + slide.name + '</span>';
    arrows.forEach(function(a, i) {
      html += ' <span class="s-arrow" id="sa' + i + '">' + a + '</span>';
    });
    stripeTip.innerHTML = html;
    stripeTip.setAttribute('data-combo', '');
    stripeTip.style.opacity = '1';

    // Long-press: immediately light the ⊙
    if (slide.type === 'longpress') {
      var s0 = document.getElementById('sa0');
      if (s0) s0.className = 's-arrow lit';
      return;
    }

    // Schedule per-arrow lighting in lockstep with the cap CSS animation
    stepTimes.forEach(function(t, i) {
      _stepTimers.push(setTimeout(function() {
        for (var j = 0; j < arrows.length; j++) {
          var span = document.getElementById('sa' + j);
          if (!span) continue;
          if (j < i)       span.className = 's-arrow done';
          else if (j === i) span.className = 's-arrow lit';
          else              span.className = 's-arrow';
        }
      }, t));
    });
  }, 150);
}

// ── Focus management ───────────────────────────────────────────────────────
function setFocus(outerIdx) {
  state.focusIdx = outerIdx;
  applyFocus(outerIdx);
  dismissHint();
}

function applyFocus(outerIdx) {
  var cells = gridCanvas.querySelectorAll('.cell');
  cells.forEach(function(cell) {
    var idx = parseInt(cell.dataset.outerIdx, 10);
    cell.classList.toggle('focused', idx === outerIdx);
  });
}

// ── Activation ─────────────────────────────────────────────────────────────
function savePosition() {
  var hasGwHome = !!(window.gwHome);
  var hasSetLast = !!(window.gwHome && window.gwHome.setLastPosition);
  var hasDebug   = !!(window.gwHome && window.gwHome.debugLog);
  var typeofSet  = window.gwHome ? typeof window.gwHome.setLastPosition : 'N/A';
  if (hasDebug) {
    window.gwHome.debugLog('savePosition row=' + state.rowIndex + ' hasGwHome=' + hasGwHome + ' hasSetLast=' + hasSetLast + ' typeof=' + typeofSet);
  }
  if (hasSetLast) {
    window.gwHome.setLastPosition(String(state.rowIndex), String(state.pageIndex));
    if (hasDebug) window.gwHome.debugLog('setLastPosition called row=' + state.rowIndex);
  }
}

function activateOuter(outerIdx) {
  var sites = currentPage();
  var site = sites[outerIdx];
  if (!site) return;

  // Sync globals immediately (Java polls them) and also fire the bridge call.
  syncPositionGlobals();
  savePosition();

  var cells = gridCanvas.querySelectorAll('.cell');
  cells.forEach(function(cell) {
    if (parseInt(cell.dataset.outerIdx, 10) === outerIdx) {
      cell.classList.add('triggered');
      setTimeout(function() { cell.classList.remove('triggered'); }, 120);
    }
  });

  setTimeout(function() {
    var url = site.url || ('https://' + site.domain);
    window.location.href = url;
  }, 80);
}

function activateFocused() {
  activateOuter(state.focusIdx);
}

// ── Navigation within the 3×3 ──────────────────────────────────────────────
function moveFocus(dir) {
  dismissHint();
  var gridPos = OUTER_TO_GRID[state.focusIdx];
  var row = Math.floor(gridPos / 3);
  var col = gridPos % 3;
  var nr = row, nc = col;

  if (dir === 'up')    nr = Math.max(0, row - 1);
  if (dir === 'down')  nr = Math.min(2, row + 1);
  if (dir === 'left')  nc = Math.max(0, col - 1);
  if (dir === 'right') nc = Math.min(2, col + 1);

  if (nr * 3 + nc === 4) {
    if (dir === 'up')    nr = Math.max(0, nr - 1);
    if (dir === 'down')  nr = Math.min(2, nr + 1);
    if (dir === 'left')  nc = Math.max(0, nc - 1);
    if (dir === 'right') nc = Math.min(2, nc + 1);
  }

  var newOuter = GRID_TO_OUTER[nr * 3 + nc];
  if (newOuter !== undefined) setFocus(newOuter);
}

// Globals read by HomeBridge.mPositionPoller via evaluateJavaScript every 2s.
// Updated whenever position changes. -1 = not yet set (init before catalog loads).
window.__gwRowIndex  = -1;
window.__gwPageIndex = 0;
function syncPositionGlobals() {
  window.__gwRowIndex  = state.rowIndex;
  window.__gwPageIndex = state.pageIndex;
}

// ── Row / page switching ───────────────────────────────────────────────────
function changeRow(delta) {
  var len = state.catalog.length;
  if (len === 0) return;
  state.rowIndex = ((state.rowIndex + delta) % len + len) % len;
  var pages = currentRow().pages.length;
  if (state.pageIndex >= pages) state.pageIndex = 0;
  dismissHint();
  render({ animate: true });
  syncPositionGlobals();
  savePosition();
}

function changePage(delta) {
  var row = currentRow();
  var pages = row.pages.length;
  if (pages === 0) return;
  state.pageIndex = ((state.pageIndex + delta) % pages + pages) % pages;
  dismissHint();
  render({ animate: true });
  syncPositionGlobals();
  savePosition();
}

// ── Hint toast ────────────────────────────────────────────────────────────
function dismissHint() {
  if (state.hintDismissed) return;
  state.hintDismissed = true;
  if (window.gwHome && window.gwHome.markHintSeen) {
    window.gwHome.markHintSeen();
  }
}

// ── Wheel / combo navigation ───────────────────────────────────────────────
var accumX = 0, accumY = 0;
var WHEEL_THRESHOLD = 36;

window.addEventListener('wheel', function(e) {
  e.preventDefault();
  accumX += e.deltaX;
  accumY += e.deltaY;

  if (Math.abs(accumY) >= WHEEL_THRESHOLD) {
    var dy = accumY;
    accumX = 0; accumY = 0;
    changeRow(dy > 0 ? 1 : -1);
  } else if (Math.abs(accumX) >= WHEEL_THRESHOLD) {
    var dx = accumX;
    accumX = 0; accumY = 0;
    changePage(dx > 0 ? 1 : -1);
  }
}, { passive: false });

// ── Keyboard handlers ──────────────────────────────────────────────────────
window.addEventListener('keydown', function(e) {
  switch (e.key) {
    case 'ArrowUp':    e.preventDefault(); moveFocus('up');    break;
    case 'ArrowDown':  e.preventDefault(); moveFocus('down');  break;
    case 'ArrowLeft':  e.preventDefault(); moveFocus('left');  break;
    case 'ArrowRight': e.preventDefault(); moveFocus('right'); break;
    case 'Enter':
    case ' ':
      e.preventDefault();
      activateFocused();
      break;
    case '2': changeRow(-1);  break;
    case '8': changeRow(1);   break;
    case '4': changePage(-1); break;
    case '6': changePage(1);  break;
  }
});

// ── Native JS bridge (window.gwHome) ──────────────────────────────────────
function bridgeCall(method) {
  var args = Array.prototype.slice.call(arguments, 1);
  return new Promise(function(resolve) {
    var id = 'r' + Date.now() + Math.random();
    window.gwHome[method].apply(window.gwHome, [id].concat(args));
    var attempts = 0;
    var poll = function() {
      if (attempts++ > 250) { resolve(null); return; } // 5 s timeout
      var result = window.gwHome.pollResult(id);
      if (result !== '') {
        try { resolve(JSON.parse(result)); } catch (_) { resolve(null); }
      } else {
        setTimeout(poll, 20);
      }
    };
    setTimeout(poll, 0);
  });
}

if (typeof window.gwHome === 'undefined') {
  window.gwHome = {};
}


// ── Bridge upgrade on load ─────────────────────────────────────────────────
function tryBridgeUpgrade() {
  if (!window.gwHome || !window.gwHome.getBookmarkCategories) return;
  if (window.gwHome.debugLog) {
    window.gwHome.debugLog('tryBridgeUpgrade: __gwLastRow=' + window.__gwLastRow + ' _savedRow=' + _savedRow + ' rowIndex=' + state.rowIndex + ' hasSetLast=' + !!(window.gwHome.setLastPosition));
  }

  bridgeCall('getPrefs').then(function(prefs) {
    if (!prefs) return;
    if (prefs.hintSeen) dismissHint();
    if (typeof prefs.is4DirMode === 'boolean') {
      var newIs8Dir = !prefs.is4DirMode;
      if (newIs8Dir !== state.is8Dir) {
        state.is8Dir = newIs8Dir;
        HUB_SLIDES = state.is8Dir ? HUB_SLIDES_8DIR : HUB_SLIDES_4DIR;
        _hubSlideIdx = 0;
        _slideQueue = [];
      }
    }
    // Always prefer SharedPreferences over the baked __gwLastRow. The baked value is written at
    // loadHomePage() time; on back navigation Chromium re-parses the same data: URI so __gwLastRow
    // reflects the position at app start, not the row the user was on when they tapped a tile.
    // SharedPreferences is updated by savePosition() immediately before every outbound navigation,
    // so it is always the most recent authoritative value.
    if (typeof prefs.lastRowIndex === 'number' && prefs.lastRowIndex >= 0) {
      _savedRow  = prefs.lastRowIndex;
      _savedPage = typeof prefs.lastColIndex === 'number' ? prefs.lastColIndex : 0;
      if (window.gwHome && window.gwHome.debugLog) {
        window.gwHome.debugLog('position-fallback: row=' + _savedRow);
      }
      if (_savedRow < state.catalog.length) {
        state.rowIndex  = _savedRow;
        var maxPage = state.catalog[_savedRow].pages.length - 1;
        state.pageIndex = Math.min(_savedPage, maxPage < 0 ? 0 : maxPage);
        syncPositionGlobals();
      }
    }
    render({});
  });

  bridgeCall('getBookmarkCategories').then(function(categories) {
    var userRows = [];
    if (categories && categories.length) {
      // Bridge already returns properly-paginated pages arrays (8 tiles/page).
      // Map to the catalog entry format used by the static catalog.
      userRows = categories.map(function(cat) {
        return {
          id:    cat.id,
          title: cat.title,
          kind:  cat.kind || 'user',
          icon:  cat.icon || '★',
          pages: (cat.pages || []),
        };
      });
      state.catalog = userRows.concat(REMOTE_CATALOG);
    }

    // Restore saved position if valid. Runs whether or not bookmark categories exist so that
    // history-cache navigation (no fresh loadHomePage injection) still lands on the right row.
    if (_savedRow >= 0 && _savedRow < state.catalog.length) {
      state.rowIndex  = _savedRow;
      var maxPage = state.catalog[_savedRow].pages.length - 1;
      state.pageIndex = Math.min(_savedPage, maxPage < 0 ? 0 : maxPage);
    } else if (userRows.length > 0) {
      // Prefer Standard Bookmarks row as default landing when no saved position.
      var bookmarksIdx = -1;
      for (var i = 0; i < userRows.length; i++) {
        if (userRows[i].id === 'bookmarks') { bookmarksIdx = i; break; }
      }
      state.rowIndex  = bookmarksIdx >= 0 ? bookmarksIdx : 0;
      state.pageIndex = 0;
    }
    _savedRow  = -1;
    _savedPage = 0;
    syncPositionGlobals();
    render({});
  });
}

// Poll for bookmark changes (replaces evaluateJavaScript push; avoids renderer-state crashes).
function startRefreshPoll() {
  setInterval(function() {
    if (window.gwHome && window.gwHome.checkRefreshPending) {
      if (window.gwHome.checkRefreshPending()) {
        tryBridgeUpgrade();
        render({});
      }
    }
  }, 500);
}

// Called by ComboDispatcher via evaluateJavaScript when the mode is toggled at runtime.
window.__gwSetComboMode = function(is8Dir) {
  if (!!is8Dir === state.is8Dir) return;
  state.is8Dir = !!is8Dir;
  HUB_SLIDES = state.is8Dir ? HUB_SLIDES_8DIR : HUB_SLIDES_4DIR;
  _hubSlideIdx = 0;
  _slideQueue = [];
  clearTimeout(_hubSlideTimer);
  var inner = document.getElementById('hub-inner');
  if (inner) {
    inner.style.opacity = '0';
    setTimeout(function() {
      populateHubInner(inner);
      inner.style.opacity = '1';
    }, 200);
  }
  updateStripeTip(HUB_SLIDES[0]);
  scheduleNextSlide();
};

// ── Init ──────────────────────────────────────────────────────────────────
(function init() {
  // Session.java injects window.__gwIs8Dir, window.__gwLastRow, window.__gwLastCol before this
  // script runs (Chromium only; Gecko falls back to bridge-less defaults).
  if (window.__gwIs8Dir) {
    state.is8Dir = true;
    HUB_SLIDES = HUB_SLIDES_8DIR;
  }
  // Restore last position from Java-injected values (read from SharedPreferences synchronously in
  // Java before the page builds — avoids the async-IPC race with void setLastPosition calls).
  if (typeof window.__gwLastRow === 'number' && window.__gwLastRow >= 0) {
    _savedRow  = window.__gwLastRow;
    _savedPage = typeof window.__gwLastCol === 'number' ? window.__gwLastCol : 0;
    if (_savedRow < state.catalog.length) {
      state.rowIndex  = _savedRow;
      var maxPage = state.catalog[_savedRow].pages.length - 1;
      state.pageIndex = Math.min(_savedPage, maxPage < 0 ? 0 : maxPage);
    }
  }

  stripeTip.textContent = HUB_TIPS[0];
  _hubTipIdx = 1;

  render({});
  scheduleNextSlide();
  setTimeout(tryBridgeUpgrade, 0);
  startRefreshPoll();
}());
