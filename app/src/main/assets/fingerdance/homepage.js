'use strict';

// ── Disc geometry constants ────────────────────────────────────────────────

const CX = 300, CY = 300;
const R_HUB        = 100;
const R_WEDGE      = 300;
const R_RIM        = 294;
const WEDGE_SPAN_DEG = 84;
const GAP_HALF_DEG   = 3;

const DIRS = ['up', 'right', 'down', 'left'];
const DIR_ANGLE = { up: 270, right: 0, down: 90, left: 180 };

const R_FAVICON = 180;
const R_LABEL   = 248;

// ── SVG math helpers ───────────────────────────────────────────────────────

function toRad(deg) { return deg * Math.PI / 180; }

function polarToCart(cx, cy, r, angleDeg) {
  const rad = toRad(angleDeg);
  return [cx + r * Math.cos(rad), cy + r * Math.sin(rad)];
}

function wedgePath(cx, cy, rInner, rOuter, centerAngle, span, gapHalf) {
  const halfSpan = span / 2 - gapHalf;
  const startDeg = centerAngle - halfSpan;
  const endDeg   = centerAngle + halfSpan;
  const [ox1, oy1] = polarToCart(cx, cy, rOuter, startDeg);
  const [ox2, oy2] = polarToCart(cx, cy, rOuter, endDeg);
  const [ix1, iy1] = polarToCart(cx, cy, rInner, startDeg);
  const [ix2, iy2] = polarToCart(cx, cy, rInner, endDeg);
  const large = halfSpan * 2 > 180 ? 1 : 0;
  return [
    `M ${ix1.toFixed(2)} ${iy1.toFixed(2)}`,
    `L ${ox1.toFixed(2)} ${oy1.toFixed(2)}`,
    `A ${rOuter} ${rOuter} 0 ${large} 1 ${ox2.toFixed(2)} ${oy2.toFixed(2)}`,
    `L ${ix2.toFixed(2)} ${iy2.toFixed(2)}`,
    `A ${rInner} ${rInner} 0 ${large} 0 ${ix1.toFixed(2)} ${iy1.toFixed(2)}`,
    'Z'
  ].join(' ');
}

function rimArcPath(cx, cy, r, centerAngle, span, gapHalf) {
  const halfSpan = span / 2 - gapHalf;
  const startDeg = centerAngle - halfSpan;
  const endDeg   = centerAngle + halfSpan;
  const [x1, y1] = polarToCart(cx, cy, r, startDeg);
  const [x2, y2] = polarToCart(cx, cy, r, endDeg);
  const large = halfSpan * 2 > 180 ? 1 : 0;
  return `M ${x1.toFixed(2)} ${y1.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(2)} ${y2.toFixed(2)}`;
}

// Position within the 600×600 disc-container (NOT viewport coords)
function discInnerPos(r, angleDeg) {
  const [x, y] = polarToCart(CX, CY, r, angleDeg);
  return { x, y };
}

// ── Bridge abstraction ─────────────────────────────────────────────────────

const _pending = new Map();

function _bridgeCall(method, ...args) {
  const id = Math.random().toString(36).slice(2) + Date.now();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (_pending.has(id)) { _pending.delete(id); reject(new Error('bridge timeout: ' + method)); }
    }, 5000);
    _pending.set(id, { resolve, reject, timer });
    window.fdHome[method](id, ...args);
  });
}

if (typeof window.fdHome !== 'undefined') {
  window.fdHome._resolve = function(id, json) {
    const p = _pending.get(id);
    if (!p) return;
    clearTimeout(p.timer);
    _pending.delete(id);
    try { p.resolve(JSON.parse(json)); } catch (e) { p.reject(e); }
  };
}

// ── Static catalog (bridge fallback) ──────────────────────────────────────

const STATIC_CATALOG = [
  { id: 'news', label: 'News', editable: false, sites: [
    { url: 'https://news.google.com',    domain: 'Google News',  iconPath: null, brandColor: '#4285F4' },
    { url: 'https://www.bbc.com',        domain: 'BBC',          iconPath: null, brandColor: '#BB1919' },
    { url: 'https://www.reuters.com',    domain: 'Reuters',      iconPath: null, brandColor: '#FF8000' },
    { url: 'https://apnews.com',         domain: 'AP News',      iconPath: null, brandColor: '#CC0000' },
  ]},
  { id: 'search', label: 'Search', editable: false, sites: [
    { url: 'https://www.google.com',     domain: 'Google',       iconPath: null, brandColor: '#4285F4' },
    { url: 'https://www.bing.com',       domain: 'Bing',         iconPath: null, brandColor: '#008373' },
    { url: 'https://duckduckgo.com',     domain: 'DuckDuckGo',   iconPath: null, brandColor: '#DE5833' },
    { url: 'https://search.brave.com',   domain: 'Brave',        iconPath: null, brandColor: '#FB542B' },
  ]},
  { id: 'social', label: 'Social', editable: false, sites: [
    { url: 'https://www.reddit.com',     domain: 'Reddit',       iconPath: null, brandColor: '#FF4500' },
    { url: 'https://www.twitter.com',    domain: 'X / Twitter',  iconPath: null, brandColor: '#1DA1F2' },
    { url: 'https://www.mastodon.social',domain: 'Mastodon',     iconPath: null, brandColor: '#6364FF' },
    { url: 'https://discord.com/app',    domain: 'Discord',      iconPath: null, brandColor: '#5865F2' },
  ]},
  { id: 'video', label: 'Video', editable: false, sites: [
    { url: 'https://www.youtube.com',    domain: 'YouTube',      iconPath: null, brandColor: '#FF0000' },
    { url: 'https://www.twitch.tv',      domain: 'Twitch',       iconPath: null, brandColor: '#9146FF' },
    { url: 'https://vimeo.com',          domain: 'Vimeo',        iconPath: null, brandColor: '#1AB7EA' },
    { url: 'https://www.netflix.com',    domain: 'Netflix',      iconPath: null, brandColor: '#E50914' },
  ]},
  { id: 'shopping', label: 'Shopping', editable: false, sites: [
    { url: 'https://www.amazon.com',     domain: 'Amazon',       iconPath: null, brandColor: '#FF9900' },
    { url: 'https://www.ebay.com',       domain: 'eBay',         iconPath: null, brandColor: '#E53238' },
    { url: 'https://www.etsy.com',       domain: 'Etsy',         iconPath: null, brandColor: '#F1641E' },
    { url: 'https://www.bestbuy.com',    domain: 'Best Buy',     iconPath: null, brandColor: '#0046BE' },
  ]},
  { id: 'tools', label: 'Tools', editable: false, sites: [
    { url: 'https://github.com',         domain: 'GitHub',       iconPath: null, brandColor: '#666666' },
    { url: 'https://stackoverflow.com',  domain: 'Stack O\'Flow',iconPath: null, brandColor: '#F48024' },
    { url: 'https://www.wikipedia.org',  domain: 'Wikipedia',    iconPath: null, brandColor: '#999999' },
    { url: 'https://translate.google.com', domain: 'Translate',  iconPath: null, brandColor: '#4285F4' },
  ]},
];

// ── App state ──────────────────────────────────────────────────────────────

let rows = [];
let currentRow  = 0;
let currentPage = 0;
let hintSeen    = false;

// ── DOM refs (overlays only — disc copies are inside snap-container) ────────

const snapContainer = document.getElementById('snap-container');
const chevronUp     = document.getElementById('chevron-up');
const chevronDown   = document.getElementById('chevron-down');
const hintToast     = document.getElementById('hint-toast');

// ── Build one snap-page: a full 1280×720 disc composition ─────────────────

function buildSnapPage(rowIdx, pageIdx, page, row) {
  const ns = 'http://www.w3.org/2000/svg';

  const pageEl = document.createElement('div');
  pageEl.className = 'snap-page';
  pageEl.dataset.row  = rowIdx;
  pageEl.dataset.page = pageIdx;

  // ── Row title
  const titleEl = document.createElement('h1');
  titleEl.className = 'row-title';
  titleEl.textContent = row.label;
  pageEl.appendChild(titleEl);

  // ── Disc container
  const discEl = document.createElement('div');
  discEl.className = 'disc-container';

  // SVG: wedges + rim arcs + hub circle
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('class', 'disc-svg');
  svg.setAttribute('viewBox', '0 0 600 600');
  svg.setAttribute('aria-hidden', 'true');

  DIRS.forEach((dir, i) => {
    const angle = DIR_ANGLE[dir];
    const site  = page[i] || null;

    // Wedge fill path
    const path = document.createElementNS(ns, 'path');
    path.setAttribute('class', 'wedge-path' + (site ? '' : ' empty'));
    path.setAttribute('data-dir', dir);
    path.setAttribute('d', wedgePath(CX, CY, R_HUB, R_WEDGE, angle, WEDGE_SPAN_DEG, GAP_HALF_DEG));
    if (site) {
      path.addEventListener('click', () => handleWedgeClick(site));
      path.addEventListener('pointerover', () => {
        path.classList.add('focused');
        const arc = svg.querySelector('.rim-arc[data-dir="' + dir + '"]');
        if (arc) arc.style.strokeWidth = '9';
      });
      path.addEventListener('pointerout', () => {
        path.classList.remove('focused');
        const arc = svg.querySelector('.rim-arc[data-dir="' + dir + '"]');
        if (arc) arc.style.strokeWidth = '6';
      });
    }
    svg.appendChild(path);

    // Rim arc (brand color)
    const arc = document.createElementNS(ns, 'path');
    arc.setAttribute('class', 'rim-arc');
    arc.setAttribute('data-dir',  dir);
    arc.setAttribute('data-row',  rowIdx);
    arc.setAttribute('data-page', pageIdx);
    arc.setAttribute('d', rimArcPath(CX, CY, R_RIM, angle, WEDGE_SPAN_DEG, GAP_HALF_DEG));
    if (site) {
      arc.style.stroke  = guardedBrandColor(site.brandColor);
      arc.style.opacity = '1';
    } else {
      arc.style.stroke  = 'transparent';
      arc.style.opacity = '0';
    }
    svg.appendChild(arc);
  });

  // Hub circle (drawn last so it occludes wedge inner edges)
  const hubCircle = document.createElementNS(ns, 'circle');
  hubCircle.setAttribute('class', 'hub-circle');
  hubCircle.setAttribute('cx', CX);
  hubCircle.setAttribute('cy', CY);
  hubCircle.setAttribute('r',  R_HUB);
  svg.appendChild(hubCircle);

  discEl.appendChild(svg);

  // Hub overlay (joystick demo + slogan) — innerHTML safe here (static markup)
  const hubEl = document.createElement('div');
  hubEl.className = 'hub-content';
  hubEl.setAttribute('aria-hidden', 'true');
  const ring = document.createElement('div');
  ring.className = 'joystick-ring';
  const dot = document.createElement('div');
  dot.className = 'joystick-dot';
  ring.appendChild(dot);
  hubEl.appendChild(ring);
  const slogan = document.createElement('p');
  slogan.className = 'hub-slogan';
  slogan.textContent = 'Push and release.';
  hubEl.appendChild(slogan);
  discEl.appendChild(hubEl);

  // Wedge content overlays: favicon + label, positioned in disc-container coords
  DIRS.forEach((dir, i) => {
    const angle = DIR_ANGLE[dir];
    const site  = page[i] || null;

    const contentEl = document.createElement('div');
    contentEl.className = 'wedge-content';
    contentEl.dataset.dir = dir;

    // Favicon at R_FAVICON
    const faviconPos  = discInnerPos(R_FAVICON, angle);
    const faviconWrap = document.createElement('div');
    faviconWrap.className = 'wedge-inner';
    faviconWrap.dataset.dir  = dir;
    faviconWrap.dataset.row  = rowIdx;
    faviconWrap.dataset.page = pageIdx;
    faviconWrap.style.left = faviconPos.x + 'px';
    faviconWrap.style.top  = faviconPos.y + 'px';
    if (site) {
      faviconWrap.appendChild(makeFaviconEl(site));
    }
    contentEl.appendChild(faviconWrap);

    // Label at R_LABEL
    const labelPos  = discInnerPos(R_LABEL, angle);
    const labelWrap = document.createElement('div');
    labelWrap.className = 'wedge-inner';
    labelWrap.style.left = labelPos.x + 'px';
    labelWrap.style.top  = labelPos.y + 'px';
    if (site) {
      const span = document.createElement('span');
      span.className = 'wedge-label';
      const t = site.domain || '';
      span.textContent = t.length > 14 ? t.slice(0, 13) + '…' : t;
      labelWrap.appendChild(span);
    }
    contentEl.appendChild(labelWrap);

    discEl.appendChild(contentEl);
  });

  pageEl.appendChild(discEl);

  // Page-position dots
  const dotsEl = document.createElement('div');
  dotsEl.className = 'page-dots';
  for (let i = 0; i < row.pages.length; i++) {
    const d = document.createElement('div');
    d.className = 'page-dot' + (i === pageIdx ? ' active' : '');
    dotsEl.appendChild(d);
  }
  pageEl.appendChild(dotsEl);

  return pageEl;
}

function makeFaviconEl(site) {
  if (site.iconPath) {
    const img = document.createElement('img');
    img.className = 'wedge-favicon';
    img.alt = site.domain || '';
    img.src = site.iconPath;
    img.onerror = () => img.replaceWith(makeLetterEl(site));
    return img;
  }
  return makeLetterEl(site);
}

function makeLetterEl(site) {
  const div = document.createElement('div');
  div.className = 'wedge-favicon placeholder';
  div.textContent = site.domain ? site.domain[0].toUpperCase() : '?';
  return div;
}

// ── Build all snap rows and pages ──────────────────────────────────────────

function buildAllSnapCells() {
  snapContainer.innerHTML = '';

  rows.forEach((row, rowIdx) => {
    const rowEl = document.createElement('div');
    rowEl.className = 'snap-row';
    rowEl.dataset.rowIndex = rowIdx;

    row.pages.forEach((page, pageIdx) => {
      rowEl.appendChild(buildSnapPage(rowIdx, pageIdx, page, row));
    });

    snapContainer.appendChild(rowEl);
  });

  // Async bookmark asset loading (favicon + brand color)
  rows.forEach((row, rowIdx) => {
    if (row.editable) {
      row.pages.forEach((page, pageIdx) => prefetchBookmarkAssets(rowIdx, pageIdx, page));
    }
  });

  // Scroll listeners (passive — no need to prevent default)
  snapContainer.addEventListener('scroll', onVerticalScroll, { passive: true });
  Array.from(snapContainer.children).forEach((rowEl, idx) => {
    rowEl.addEventListener('scroll', () => onHorizontalScroll(idx), { passive: true });
  });

  // Restore saved position without animation
  scrollToPosition(currentRow, currentPage, false);
  updateChevrons();
}

// ── Scroll position management ─────────────────────────────────────────────

function scrollToPosition(row, page, smooth) {
  const behavior = smooth ? 'smooth' : 'instant';
  snapContainer.scrollTo({ top: row * 720, behavior: behavior });
  const rowEl = snapContainer.children[row];
  if (rowEl) rowEl.scrollTo({ left: page * 1280, behavior: behavior });
}

function onVerticalScroll() {
  const newRow = Math.round(snapContainer.scrollTop / 720);
  if (newRow < 0 || newRow >= rows.length || newRow === currentRow) return;
  currentRow = newRow;
  const rowEl = snapContainer.children[newRow];
  if (rowEl) {
    const p = Math.round(rowEl.scrollLeft / 1280);
    currentPage = Math.max(0, Math.min(p, rows[newRow].pages.length - 1));
  } else {
    currentPage = 0;
  }
  onNavigated();
}

function onHorizontalScroll(rowIdx) {
  if (rowIdx !== currentRow) return;
  const rowEl = snapContainer.children[rowIdx];
  if (!rowEl) return;
  const newPage = Math.round(rowEl.scrollLeft / 1280);
  if (newPage < 0 || newPage >= rows[rowIdx].pages.length || newPage === currentPage) return;
  currentPage = newPage;
  onNavigated();
}

function onNavigated() {
  if (!hintSeen) {
    hintSeen = true;
    hintToast.classList.add('hidden');
    if (typeof window.fdHome !== 'undefined') {
      try { window.fdHome.markHintSeen(); } catch (_) {}
    }
  }
  updateChevrons();
  if (typeof window.fdHome !== 'undefined') {
    try { window.fdHome.setLastPosition(String(currentRow), String(currentPage)); } catch (_) {}
  }
}

function updateChevrons() {
  chevronUp.classList.toggle('hidden', currentRow === 0);
  chevronDown.classList.toggle('hidden', currentRow === rows.length - 1);
}

// ── Wedge click ────────────────────────────────────────────────────────────

function handleWedgeClick(site) {
  if (!site || !site.url) return;
  if (typeof window.fdHome !== 'undefined') {
    window.fdHome.openUrl(site.url);
  } else {
    window.location.href = site.url;
  }
}

// ── Brand color guard ──────────────────────────────────────────────────────

function guardedBrandColor(hex) {
  if (!hex || !/^#[0-9a-fA-F]{6}$/.test(hex)) {
    return 'rgba(160,163,208,0.6)';
  }
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  const surfaceHiLum = 0.11;
  if (Math.abs(lum - surfaceHiLum) < 0.059) {
    const lr = Math.min(255, Math.round(r + (255 - r) * 0.25));
    const lg = Math.min(255, Math.round(g + (255 - g) * 0.25));
    const lb = Math.min(255, Math.round(b + (255 - b) * 0.25));
    return 'rgb(' + lr + ',' + lg + ',' + lb + ')';
  }
  return hex;
}

// ── Async bookmark favicon/color loading ───────────────────────────────────

function prefetchBookmarkAssets(rowIdx, pageIdx, page) {
  if (!window.fdHome) return;
  page.forEach((site, i) => {
    if (!site || !site._bookmark || !site.url) return;
    const dir = DIRS[i];

    _bridgeCall('getFavicon', site.url).then(dataUrl => {
      if (!dataUrl) return;
      const wrap = snapContainer.querySelector(
        '.wedge-inner[data-row="' + rowIdx + '"][data-page="' + pageIdx + '"][data-dir="' + dir + '"]'
      );
      if (!wrap) return;
      const existing = wrap.firstChild;
      if (existing) {
        const img = document.createElement('img');
        img.className = 'wedge-favicon';
        img.alt = site.domain || '';
        img.src = dataUrl;
        img.onerror = () => img.replaceWith(makeLetterEl(site));
        existing.replaceWith(img);
      }
    }).catch(() => {});

    _bridgeCall('getBrandColor', site.url).then(hex => {
      if (!hex) return;
      const arc = snapContainer.querySelector(
        '.rim-arc[data-row="' + rowIdx + '"][data-page="' + pageIdx + '"][data-dir="' + dir + '"]'
      );
      if (arc) arc.style.stroke = guardedBrandColor(hex);
    }).catch(() => {});
  });
}

// ── Data helpers ───────────────────────────────────────────────────────────

function paginateSites(sites) {
  const pages = [];
  for (let i = 0; i < sites.length; i += 4) pages.push(sites.slice(i, i + 4));
  if (pages.length === 0) pages.push([]);
  return pages;
}

function normalizeBookmarkSite(item) {
  let domain = item.title || '';
  try {
    const host = new URL(item.url).hostname.replace(/^www\./, '');
    if (host) domain = host;
  } catch (_) {}
  return { url: item.url, domain, iconPath: null, brandColor: null, _bookmark: true };
}

// ── Data loading ───────────────────────────────────────────────────────────

async function loadData() {
  let catalog = STATIC_CATALOG;
  let folders = [];

  if (typeof window.fdHome !== 'undefined') {
    try { catalog = await _bridgeCall('getCatalog'); } catch (_) {}
    try { folders = await _bridgeCall('getFolders'); } catch (_) {}
  }

  let savedRow = 0, savedPage = 0;
  if (typeof window.fdHome !== 'undefined') {
    try {
      const prefs = await _bridgeCall('getPrefs');
      savedRow  = prefs.lastRowIndex  || 0;
      savedPage = prefs.lastColIndex  || 0;
      hintSeen  = !!prefs.hintSeen;
    } catch (_) {}
  }

  rows = [];
  folders.forEach(f => {
    rows.push({
      label: f.title,
      editable: true,
      guid: f.guid,
      pages: paginateSites(f.items.map(normalizeBookmarkSite)),
    });
  });
  catalog.forEach(cat => {
    rows.push({
      label: cat.label,
      editable: false,
      id: cat.id,
      pages: paginateSites(cat.sites),
    });
  });
  if (rows.length === 0) rows = [{ label: '—', editable: false, pages: [[]] }];

  currentRow  = Math.min(savedRow,  rows.length - 1);
  currentPage = Math.min(savedPage, rows[currentRow].pages.length - 1);

  // Show hint unless already seen
  if (!hintSeen) hintToast.classList.remove('hidden');

  buildAllSnapCells();
}

// ── Chevron clicks ─────────────────────────────────────────────────────────

chevronUp.addEventListener('click', () => {
  if (currentRow > 0) scrollToPosition(currentRow - 1, currentPage, true);
});
chevronDown.addEventListener('click', () => {
  if (currentRow < rows.length - 1) scrollToPosition(currentRow + 1, currentPage, true);
});

// ── Keyboard (desktop debug) ───────────────────────────────────────────────

window.addEventListener('keydown', e => {
  const map = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  const dir = map[e.key];
  if (!dir) return;
  e.preventDefault();
  if (dir === 'up'    && currentRow > 0)
    scrollToPosition(currentRow - 1, currentPage, true);
  else if (dir === 'down'  && currentRow < rows.length - 1)
    scrollToPosition(currentRow + 1, currentPage, true);
  else if (dir === 'left'  && currentPage > 0)
    scrollToPosition(currentRow, currentPage - 1, true);
  else if (dir === 'right' && currentPage < rows[currentRow].pages.length - 1)
    scrollToPosition(currentRow, currentPage + 1, true);
});

// ── Init ───────────────────────────────────────────────────────────────────

loadData();
