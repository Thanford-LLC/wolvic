'use strict';

// ── Catalog data ───────────────────────────────────────────────────────────
// Categories per PROJECT.md §8.2: Video / VR / Search / Gaming / Tools / Social.
// Each site: { name, domain, icon (filename in icons/), color (brand), letter (fallback) }
// Icons are 64×64 PNGs bundled in assets-gw-*/glyphew/icons/.

const STATIC_CATALOG = [
  {
    id: 'video', title: 'Video', icon: '▶',
    pages: [[
      { name: 'YouTube',    domain: 'youtube.com',     icon: 'youtube.png',    color: '#FF0033', letter: '▶' },
      { name: 'Netflix',    domain: 'netflix.com',     icon: 'netflix.png',    color: '#E50914', letter: 'N' },
      { name: 'Twitch',     domain: 'twitch.tv',       icon: 'twitch.png',     color: '#9146FF', letter: 'T' },
      { name: 'Vimeo',      domain: 'vimeo.com',       icon: 'vimeo.png',      color: '#1ab7ea', letter: 'V' },
      { name: 'Prime',      domain: 'primevideo.com',  icon: 'primevideo.png', color: '#00A8E1', letter: '▷' },
      { name: 'Disney+',    domain: 'disneyplus.com',  icon: 'disneyplus.png', color: '#1A1D29', letter: 'D+' },
      { name: 'Apple TV',   domain: 'tv.apple.com',    icon: 'appletv.png',    color: '#FFFFFF', letter: '' },
      { name: 'Plex',       domain: 'plex.tv',         icon: 'plex.png',       color: '#E5A00D', letter: 'P' },
    ]],
  },
  {
    id: 'vr', title: 'VR', icon: '◎',
    pages: [[
      { name: 'Quest Store',domain: 'meta.com/quest',  icon: 'metaquest.png',    color: '#0668E1', letter: 'Q' },
      { name: 'SideQuest',  domain: 'sidequestvr.com', icon: 'sidequest.png',    color: '#FF5722', letter: '▽' },
      { name: 'UploadVR',   domain: 'uploadvr.com',    icon: 'uploadvr.png',     color: '#1CAAD9', letter: 'U' },
      { name: 'WebXR',      domain: 'immersive-web.github.io', icon: 'immersiveweb.png', color: '#FFFFFF', letter: 'X' },
      { name: 'VR Focus',   domain: 'vrfocus.com',     icon: 'vrfocus.png',      color: '#E02020', letter: 'V' },
      { name: 'VRScout',    domain: 'vrscout.com',     icon: 'vrscout.png',      color: '#222222', letter: 'V' },
      { name: 'Road to VR', domain: 'roadtovr.com',    icon: 'roadtovr.png',     color: '#B00000', letter: 'R' },
      { name: 'Within',     domain: 'within.com',      icon: 'within.png',       color: '#FF5C1A', letter: 'W' },
    ]],
  },
  {
    id: 'search', title: 'Search', icon: '⊙',
    pages: [[
      { name: 'Google',     domain: 'google.com',       icon: 'google.png',     color: '#4285F4', letter: 'G' },
      { name: 'Bing',       domain: 'bing.com',          icon: 'bing.png',       color: '#008373', letter: 'b' },
      { name: 'DuckDuckGo', domain: 'duckduckgo.com',    icon: 'duckduckgo.png', color: '#DE5833', letter: 'D' },
      { name: 'Brave',      domain: 'search.brave.com',  icon: 'brave.png',      color: '#FB542B', letter: '▲' },
      { name: 'Kagi',       domain: 'kagi.com',          icon: 'kagi.png',       color: '#FFB319', letter: 'K' },
      { name: 'Perplexity', domain: 'perplexity.ai',     icon: 'perplexity.png', color: '#20808D', letter: 'P' },
      { name: 'Startpage',  domain: 'startpage.com',     icon: 'startpage.png',  color: '#5046E4', letter: 'S' },
      { name: 'Ecosia',     domain: 'ecosia.org',        icon: 'ecosia.png',     color: '#22885B', letter: '🌿' },
    ]],
  },
  {
    id: 'gaming', title: 'Gaming', icon: '⊞',
    pages: [[
      { name: 'Steam',      domain: 'store.steampowered.com', icon: 'steam.png',     color: '#1B2838', letter: 'S' },
      { name: 'Twitch',     domain: 'twitch.tv',              icon: 'twitch.png',    color: '#9146FF', letter: 'T' },
      { name: 'Epic',       domain: 'epicgames.com',          icon: 'epicgames.png', color: '#2A2A2A', letter: 'E' },
      { name: 'GOG',        domain: 'gog.com',                icon: 'gog.png',       color: '#86328A', letter: 'G' },
      { name: 'Reddit',     domain: 'reddit.com/r/gaming',    icon: 'reddit.png',    color: '#FF4500', letter: 'r' },
      { name: 'IGN',        domain: 'ign.com',                icon: 'ign.png',       color: '#BF1313', letter: '▣' },
      { name: 'Polygon',    domain: 'polygon.com',            icon: 'polygon.png',   color: '#FF4060', letter: '▶' },
      { name: 'Kotaku',     domain: 'kotaku.com',             icon: 'kotaku.png',    color: '#000000', letter: 'K' },
    ]],
  },
  {
    id: 'tools', title: 'Tools', icon: '⚙',
    pages: [[
      { name: 'GitHub',     domain: 'github.com',      icon: 'github.png',       color: '#FFFFFF', letter: 'G' },
      { name: 'StackOverflow', domain: 'stackoverflow.com', icon: 'stackoverflow.png', color: '#F48024', letter: 'S' },
      { name: 'MDN',        domain: 'developer.mozilla.org', icon: 'mdn.png',    color: '#4A4A4A', letter: 'M' },
      { name: 'W3Schools',  domain: 'w3schools.com',   icon: 'w3schools.png',    color: '#4CAF50', letter: 'W' },
      { name: 'Translate',  domain: 'translate.google.com',  icon: 'translate.png', color: '#4285F4', letter: 'T' },
      { name: 'Wikipedia',  domain: 'wikipedia.org',   icon: 'wikipedia.png',    color: '#FFFFFF', letter: 'W' },
      { name: 'Archive',    domain: 'archive.org',     icon: 'archive.png',      color: '#888888', letter: 'A' },
      { name: 'Wolfram',    domain: 'wolframalpha.com', icon: 'wolframalpha.png', color: '#E47000', letter: 'W' },
    ]],
  },
  {
    id: 'social', title: 'Social', icon: '◉',
    pages: [[
      { name: 'Reddit',     domain: 'reddit.com',      icon: 'reddit.png',    color: '#FF4500', letter: 'r' },
      { name: 'Twitter/X',  domain: 'x.com',           icon: 'twitter.png',   color: '#FFFFFF', letter: '𝕏' },
      { name: 'Instagram',  domain: 'instagram.com',   icon: 'instagram.png', color: '#E4405F', letter: '○' },
      { name: 'Discord',    domain: 'discord.com',     icon: 'discord.png',   color: '#5865F2', letter: 'D' },
      { name: 'Mastodon',   domain: 'mastodon.social', icon: 'mastodon.png',  color: '#6364FF', letter: 'M' },
      { name: 'Facebook',   domain: 'facebook.com',    icon: 'facebook.png',  color: '#1877F2', letter: 'f' },
      { name: 'LinkedIn',   domain: 'linkedin.com',    icon: 'linkedin.png',  color: '#0A66C2', letter: 'in' },
      { name: 'Hacker News',domain: 'news.ycombinator.com', icon: 'hackernews.png', color: '#FF6600', letter: 'Y' },
    ]],
  },
];

// Tip text pool — rotated every 6s in the center hub.
const HUB_TIPS = [
  'HOLD GRIP · FLICK · RELEASE',
  'PUSH UP/DOWN TO SWITCH CATEGORY',
  'PUSH LEFT/RIGHT TO SWITCH SETS',
  'CENTER IS HOME',
  'COMBOS NEED GRIP',
  'LONG-PRESS OPENS SETTINGS',
  'FLICK & RELEASE',
  'JOYSTICK MOVES FOCUS',
];

// ── Grid slot mapping ──────────────────────────────────────────────────────
// 3×3 grid positions 0-8. Center = 4. Outer slots: 0,1,2,3,5,6,7,8 → catalog idx 0-7.
// OUTER_TO_GRID[outerIdx] → gridPos (0-8, skipping 4)
const OUTER_TO_GRID = [0, 1, 2, 3, 5, 6, 7, 8];
// GRID_TO_OUTER: gridPos → outerIdx (only for non-center slots)
const GRID_TO_OUTER = { 0:0, 1:1, 2:2, 3:3, 5:4, 6:5, 7:6, 8:7 };

// ── State ──────────────────────────────────────────────────────────────────
const state = {
  catalog: STATIC_CATALOG,   // may be augmented by bridge on load
  rowIndex: 0,
  pageIndex: 0,
  focusIdx: 0,               // outer slot 0-7 currently focused
  hintDismissed: false,
  hubTipIdx: 0,
};

// ── DOM refs ───────────────────────────────────────────────────────────────
const $ = (id) => document.getElementById(id);
const headerKind   = $('header-kind');
const headerTitle  = $('header-title');
const headerDots   = $('header-dots');
const rowRailEl    = $('row-rail');
const chevronN     = $('chevron-n');
const chevronS     = $('chevron-s');
const gridCanvas   = $('grid-canvas');
const hintToast    = $('hint-toast');

// ── Render ─────────────────────────────────────────────────────────────────
function currentRow() {
  return state.catalog[state.rowIndex] || state.catalog[0];
}

function currentPage() {
  const row = currentRow();
  return (row.pages[state.pageIndex] || row.pages[0]) || [];
}

function render(opts) {
  const row = currentRow();
  opts = opts || {};

  // Header
  headerKind.textContent  = row.kind === 'user' ? 'BOOKMARKS' : 'CATEGORY';
  headerTitle.textContent = row.title;
  renderDots(row.pages.length, state.pageIndex);

  // Rail
  renderRail();

  // Chevrons
  chevronN.classList.toggle('hidden', state.rowIndex === 0);
  chevronS.classList.toggle('hidden', state.rowIndex === state.catalog.length - 1);

  // Grid — cross-fade then render
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
    // Determine row/col in the 3×3
    var gr = Math.floor(gridPos / 3);
    var gc = gridPos % 3;

    // Before center slot, insert center
    if (gc === 0 && gr === 1) {
      // We're inserting row 1: left slot (gridPos=3), then center (4), then right (5)
      // gridPos=3 is outerIdx=3, gridPos=5 is outerIdx=4
      // But our loop visits them in order, so center needs to be injected
    }

    var slot = document.createElement('div');
    slot.className = 'cell-slot';

    // Inject center hub before slot for gridPos === 5 (right slot of middle row)
    // i.e., after gridPos===3 (outerIdx=3) we inject the center
    if (outerIdx === 3) {
      gridCanvas.appendChild(slot); // left middle slot (gridPos=3)
      slot.appendChild(makeSiteCell(sites[outerIdx], outerIdx));

      // Now inject center hub slot
      var centerSlot = document.createElement('div');
      centerSlot.className = 'cell-slot center-slot';
      centerSlot.appendChild(makeCenterHub());
      gridCanvas.appendChild(centerSlot);
      return;
    }

    var siteEl = makeSiteCell(sites[outerIdx], outerIdx);
    slot.appendChild(siteEl);
    gridCanvas.appendChild(slot);
  });

  // Apply focus to current outer slot
  applyFocus(state.focusIdx);
}

function makeSiteCell(site, outerIdx) {
  if (!site) {
    var empty = document.createElement('div');
    empty.className = 'cell cell-empty';
    var emptyTile = document.createElement('div');
    emptyTile.className = 'cell-tile';
    empty.appendChild(emptyTile);
    var emptyLabel = document.createElement('div');
    emptyLabel.className = 'cell-label';
    empty.appendChild(emptyLabel);
    return empty;
  }

  var cell = document.createElement('div');
  cell.className = 'cell';
  cell.dataset.outerIdx = outerIdx;

  var tile = document.createElement('div');
  tile.className = 'cell-tile';

  // Favicon img with letter fallback
  var img = document.createElement('img');
  img.className = 'cell-favicon';
  img.alt = site.name;
  img.src = 'icons/' + site.icon;
  img.addEventListener('error', function() {
    // Chromium: try bridge resolution
    if (window.gwHome && typeof window.gwHome.getIcon === 'function') {
      var dataUrl = window.gwHome.getIcon(site.icon);
      if (dataUrl) { img.src = dataUrl; return; }
    }
    // Ultimate fallback: show letter glyph
    img.style.display = 'none';
    var glyph = document.createElement('div');
    glyph.className = 'cell-glyph';
    glyph.textContent = site.letter || site.name[0];
    tile.insertBefore(glyph, tile.firstChild);
  });
  tile.appendChild(img);

  // Brand color dot (content-tier, per BRAND.md)
  if (site.color) {
    var dot = document.createElement('div');
    dot.className = 'brand-dot';
    dot.style.background = site.color;
    tile.appendChild(dot);
  }

  cell.appendChild(tile);

  var label = document.createElement('div');
  label.className = 'cell-label';
  label.textContent = site.name;
  cell.appendChild(label);

  // Activation: trigger flash then navigate
  cell.addEventListener('pointerdown', function() { setFocus(outerIdx); });
  cell.addEventListener('click', function() { activateOuter(outerIdx); });

  return cell;
}

function makeCenterHub() {
  var hub = document.createElement('div');
  hub.className = 'hub';
  hub.id = 'hub-center';

  // Passive joystick illustration (CSS-animated cap)
  var wrap = document.createElement('div');
  wrap.className = 'joystick-wrap';

  var face = document.createElement('div');
  face.className = 'joystick-face';

  var well = document.createElement('div');
  well.className = 'joystick-well';

  // 8 directional pips
  [
    ['n',  true], ['ne', false], ['e',  true], ['se', false],
    ['s',  true], ['sw', false], ['w',  true], ['nw', false],
  ].forEach(function(entry) {
    var pip = document.createElement('div');
    pip.className = 'joy-pip ' + entry[0] + (entry[1] ? ' cardinal' : ' diagonal');
    well.appendChild(pip);
  });

  // Animated thumb cap
  var cap = document.createElement('div');
  cap.className = 'joy-cap';
  well.appendChild(cap);

  face.appendChild(well);
  wrap.appendChild(face);
  hub.appendChild(wrap);

  // Tip text (rotated every 6s by JS)
  var tip = document.createElement('div');
  tip.className = 'hub-tip';
  tip.id = 'hub-tip';
  tip.textContent = HUB_TIPS[0];
  hub.appendChild(tip);

  return hub;
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
function activateOuter(outerIdx) {
  var sites = currentPage();
  var site = sites[outerIdx];
  if (!site) return;

  // Brief trigger flash
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

  // Skip center (4)
  if (nr * 3 + nc === 4) {
    if (dir === 'up')    nr = Math.max(0, nr - 1);
    if (dir === 'down')  nr = Math.min(2, nr + 1);
    if (dir === 'left')  nc = Math.max(0, nc - 1);
    if (dir === 'right') nc = Math.min(2, nc + 1);
  }

  var newGridPos = nr * 3 + nc;
  var newOuter = GRID_TO_OUTER[newGridPos];
  if (newOuter !== undefined) {
    setFocus(newOuter);
  }
}

// ── Row / page switching ───────────────────────────────────────────────────
// Called by: keyboard combo keys (2/4/6/8) AND window.gwHome._onCombo().
function changeRow(delta) {
  var next = state.rowIndex + delta;
  if (next < 0 || next >= state.catalog.length) return;
  state.rowIndex = next;
  // Clamp pageIndex to valid range for new row
  var pages = currentRow().pages.length;
  if (state.pageIndex >= pages) state.pageIndex = pages - 1;
  dismissHint();
  render({ animate: true });
}

function changePage(delta) {
  var row = currentRow();
  var next = state.pageIndex + delta;
  if (next < 0 || next >= row.pages.length) return;
  state.pageIndex = next;
  dismissHint();
  render({ animate: true });
}

// ── Hint toast ────────────────────────────────────────────────────────────
function dismissHint() {
  if (state.hintDismissed) return;
  state.hintDismissed = true;
  hintToast.classList.add('dismissed');
  if (window.gwHome && typeof window.gwHome.markHintSeen === 'function') {
    window.gwHome.markHintSeen();
  }
}

// ── Hub tip rotation (every 6s) ────────────────────────────────────────────
function rotateTip() {
  var tip = document.getElementById('hub-tip');
  if (!tip) return;
  state.hubTipIdx = (state.hubTipIdx + 1) % HUB_TIPS.length;
  tip.style.opacity = '0';
  setTimeout(function() {
    tip.textContent = HUB_TIPS[state.hubTipIdx];
    tip.style.opacity = '0.9';
  }, 200);
}

// ── Wheel navigation (joystick without grip) ──────────────────────────────
var accumX = 0, accumY = 0;
var WHEEL_THRESHOLD = 36;

window.addEventListener('wheel', function(e) {
  e.preventDefault();
  accumX += e.deltaX;
  accumY += e.deltaY;

  var gridPos = OUTER_TO_GRID[state.focusIdx];
  var row = Math.floor(gridPos / 3);
  var col = gridPos % 3;

  if (accumY >= WHEEL_THRESHOLD) {
    accumY = 0;
    // At bottom row: change category row
    if (row === 2) { changeRow(1); return; }
    moveFocus('down');
  } else if (accumY <= -WHEEL_THRESHOLD) {
    accumY = 0;
    if (row === 0) { changeRow(-1); return; }
    moveFocus('up');
  }
  if (accumX >= WHEEL_THRESHOLD) {
    accumX = 0;
    // At right column: change page
    if (col === 2) { changePage(1); return; }
    moveFocus('right');
  } else if (accumX <= -WHEEL_THRESHOLD) {
    accumX = 0;
    if (col === 0) { changePage(-1); return; }
    moveFocus('left');
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
    // Shortcut keys for row/page during dev/testing
    case '2': changeRow(-1);  break;
    case '8': changeRow(1);   break;
    case '4': changePage(-1); break;
    case '6': changePage(1);  break;
  }
});

// ── Native JS bridge (window.gwHome) ──────────────────────────────────────
// Async resolve pattern: bridge calls window.gwHome._resolve(id, json).
var _pendingResolvers = {};

function bridgeCall(method) {
  var args = Array.prototype.slice.call(arguments, 1);
  return new Promise(function(resolve) {
    var id = 'r' + Date.now() + Math.random();
    _pendingResolvers[id] = function(json) {
      delete _pendingResolvers[id];
      try { resolve(JSON.parse(json)); } catch (_) { resolve(null); }
    };
    window.gwHome[method].apply(window.gwHome, [id].concat(args));
  });
}

// Exposed for the bridge resolve callback
if (typeof window.gwHome === 'undefined') {
  window.gwHome = {};
}
window.gwHome._resolve = function(id, json) {
  if (_pendingResolvers[id]) _pendingResolvers[id](json);
};

// Called by native ComboDispatcher once wired (future PR).
window.gwHome._onCombo = function(direction) {
  switch (direction) {
    case 2: changeRow(-1);  break;
    case 8: changeRow(1);   break;
    case 4: changePage(-1); break;
    case 6: changePage(1);  break;
  }
};

// ── Bridge upgrade on load ─────────────────────────────────────────────────
function tryBridgeUpgrade() {
  if (!window.gwHome || typeof window.gwHome.getFolders !== 'function') return;

  // Restore last position
  bridgeCall('getPrefs').then(function(prefs) {
    if (!prefs) return;
    if (prefs.hintSeen) dismissHint();
    if (typeof prefs.lastRowIndex === 'number' && prefs.lastRowIndex < state.catalog.length) {
      state.rowIndex = prefs.lastRowIndex;
    }
    if (typeof prefs.lastColIndex === 'number') {
      var pages = currentRow().pages.length;
      state.pageIndex = Math.min(prefs.lastColIndex, pages - 1);
    }
    render({});
  });

  // Auto-detect bookmark folders and prepend as user rows
  bridgeCall('getFolders').then(function(folders) {
    if (!folders || !folders.length) return;
    var userRows = folders.map(function(f) {
      return {
        id:    'bm-' + (f.guid || f.title),
        title: f.title || 'Bookmarks',
        kind:  'user',
        icon:  '★',
        pages: [
          (f.items || []).slice(0, 8).map(function(item) {
            return {
              name:   item.title || item.url,
              domain: (item.url || '').replace(/^https?:\/\//, '').split('/')[0],
              icon:   null,
              color:  null,
              letter: (item.title || 'B')[0].toUpperCase(),
              url:    item.url,
            };
          }),
        ],
      };
    });
    state.catalog = userRows.concat(STATIC_CATALOG);
    state.rowIndex = userRows.length; // land on first STATIC_CATALOG row
    render({});
  });
}

// ── Init ──────────────────────────────────────────────────────────────────
(function init() {
  render({});

  // Hub tip rotation (6s interval)
  setInterval(rotateTip, 6000);

  // Bridge upgrade (after first paint)
  setTimeout(tryBridgeUpgrade, 0);
}());
