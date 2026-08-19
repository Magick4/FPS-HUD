/* Live FPS HUD - WebUI control panel (KernelSU / KernelSU-Next / APatch / Magisk) */

const CLI = 'sh /data/adb/modules/fox_live_fps/system/bin/fpshud';

const DEFAULTS = {
  enabled: 'true',
  source: 'auto',
  node: '',
  interval: '500',
  position: 'top_left',
  x: '16',
  y: '16',
  text_size: '13',
  text_color: '#00E676',
  bg_color: '#99000000',
  bg_radius: '8',
  padding_h: '8',
  padding_v: '3',
  opacity: '1',
  label: 'FPS',
  show_hz: 'false',
  decimals: '0',
  dynamic_color: 'false',
  color_good: '#00E676',
  color_ok: '#FFC107',
  color_bad: '#FF5252',
  window_type: 'secure',
  fps_period_ms: '0',
  boot_delay: '10'
};

let cfg = Object.assign({}, DEFAULTS);
let demo = false;
let previewFps = 60;

/* ------------------------------------------------------------------ root */

const hasKsu = typeof ksu !== 'undefined' && ksu && typeof ksu.exec === 'function';
let cbId = 0;

function exec(cmd) {
  if (!hasKsu) return Promise.resolve({ errno: -1, stdout: '', stderr: 'no root bridge' });
  return new Promise((resolve) => {
    const name = 'fpshud_cb_' + Date.now() + '_' + cbId++;
    window[name] = (errno, stdout, stderr) => {
      delete window[name];
      resolve({ errno, stdout: stdout || '', stderr: stderr || '' });
    };
    try {
      ksu.exec(cmd, JSON.stringify({}), name);
    } catch (e) {
      delete window[name];
      resolve({ errno: -1, stdout: '', stderr: String(e) });
    }
  });
}

const q = (v) => "'" + String(v).replace(/'/g, "'\\''") + "'";
const $ = (id) => document.getElementById(id);

function toast(msg) {
  if (hasKsu && typeof ksu.toast === 'function') { try { ksu.toast(msg); } catch (e) { /* ignore */ } }
  const t = $('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.remove('show'), 1800);
}

/* ---------------------------------------------------------------- config */

async function loadConfig() {
  if (!hasKsu) {
    demo = true;
    const saved = localStorage.getItem('fpshud-demo');
    cfg = Object.assign({}, DEFAULTS, saved ? JSON.parse(saved) : {});
    $('subtitle').textContent = 'preview mode — no root bridge';
    return;
  }
  const { stdout } = await exec(CLI + ' get');
  const parsed = {};
  stdout.split('\n').forEach((line) => {
    const i = line.indexOf('=');
    if (i > 0) parsed[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  });
  cfg = Object.assign({}, DEFAULTS, parsed);
}

const pending = {};
let flushTimer = null;

function set(key, value) {
  value = String(value);
  if (cfg[key] === value) { paint(); return; }
  cfg[key] = value;
  paint();
  if (demo) {
    localStorage.setItem('fpshud-demo', JSON.stringify(cfg));
    return;
  }
  pending[key] = value;
  clearTimeout(flushTimer);
  flushTimer = setTimeout(flush, 250);
}

async function flush() {
  const keys = Object.keys(pending);
  if (!keys.length) return;
  const cmd = keys.map((k) => CLI + ' set ' + k + ' ' + q(pending[k])).join(' && ');
  keys.forEach((k) => delete pending[k]);
  const res = await exec(cmd);
  if (res.errno !== 0) toast('could not save: ' + (res.stderr || res.errno));
}

/* --------------------------------------------------------------- colours */

const rgbOf = (c) => {
  c = (c || '').replace('#', '');
  if (c.length === 8) c = c.slice(2);
  if (c.length !== 6) return '#000000';
  return '#' + c.toLowerCase();
};

const alphaOf = (c) => {
  c = (c || '').replace('#', '');
  return c.length === 8 ? Math.round((parseInt(c.slice(0, 2), 16) / 255) * 100) : 100;
};

const argb = (rgb, pct) => {
  const a = Math.round((pct / 100) * 255).toString(16).padStart(2, '0');
  return ('#' + a + rgb.replace('#', '')).toUpperCase();
};

const cssColor = (c, extraAlpha) => {
  c = (c || '').replace('#', '');
  let a = 1;
  if (c.length === 8) { a = parseInt(c.slice(0, 2), 16) / 255; c = c.slice(2); }
  if (c.length !== 6) return 'transparent';
  const n = parseInt(c, 16);
  a *= extraAlpha === undefined ? 1 : extraAlpha;
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
};

/* --------------------------------------------------------------- binding */

function bindSlider(id, fmt, transform, outId) {
  const el = $(id);
  const out = $(outId || id + 'Out');
  const write = (commit) => {
    const v = transform ? transform(el.value) : el.value;
    if (out) out.textContent = fmt(el.value);
    if (commit) set(id, v);
    else { cfg[id] = String(v); paint(); }
  };
  el.addEventListener('input', () => write(false));
  el.addEventListener('change', () => write(true));
}

function bindSeg(id) {
  $(id).querySelectorAll('button').forEach((b) => {
    b.addEventListener('click', () => set(id, b.dataset.val));
  });
}

function bindSwitch(id) {
  $(id).addEventListener('change', (e) => set(id, e.target.checked ? 'true' : 'false'));
}

function bindColor(id) {
  const el = $(id);
  el.addEventListener('input', () => { cfg[id] = el.value.toUpperCase(); paint(); });
  el.addEventListener('change', () => set(id, el.value.toUpperCase()));
}

function wire() {
  bindSwitch('enabled');
  bindSwitch('show_hz');
  bindSwitch('dynamic_color');

  $('grid').querySelectorAll('button').forEach((b) => {
    b.addEventListener('click', () => set('position', b.dataset.pos));
  });

  bindSlider('x', (v) => v + ' dp');
  bindSlider('y', (v) => v + ' dp');
  bindSlider('text_size', (v) => v + ' sp');
  bindSlider('bg_radius', (v) => v + ' dp');
  bindSlider('interval', (v) => v + ' ms');
  bindSlider('fps_period_ms', (v) => (+v === 0 ? 'kernel default' : v + ' ms'));
  bindSlider('opacity', (v) => v + '%', (v) => (v / 100).toFixed(2));
  bindSlider('padding_h', () => padText(), null, 'padOut');
  bindSlider('padding_v', () => padText(), null, 'padOut');

  bindSeg('decimals');
  bindSeg('source');
  bindSeg('window_type');

  bindColor('text_color');
  bindColor('color_good');
  bindColor('color_ok');
  bindColor('color_bad');

  $('swatches').querySelectorAll('button').forEach((b) => {
    b.addEventListener('click', () => set('text_color', b.dataset.color));
  });

  const bgWrite = (commit) => {
    const v = argb($('bg_rgb').value, +$('bgAlpha').value);
    $('bgAlphaOut').textContent = $('bgAlpha').value + '%';
    if (commit) set('bg_color', v); else { cfg.bg_color = v; paint(); }
  };
  $('bg_rgb').addEventListener('input', () => bgWrite(false));
  $('bg_rgb').addEventListener('change', () => bgWrite(true));
  $('bgAlpha').addEventListener('input', () => bgWrite(false));
  $('bgAlpha').addEventListener('change', () => bgWrite(true));

  $('label').addEventListener('change', (e) => set('label', e.target.value.trim() || 'none'));
  $('node').addEventListener('change', (e) => set('node', e.target.value));

  $('btnRestart').addEventListener('click', async () => {
    toast('restarting…');
    const r = await exec(CLI + ' restart');
    show(r.stdout + r.stderr);
    refresh();
  });
  $('btnProbe').addEventListener('click', async () => {
    const r = await exec(CLI + ' probe');
    show(r.stdout || r.stderr || 'nothing to report');
  });
  $('btnLog').addEventListener('click', async () => {
    const r = await exec(CLI + ' log 80');
    show(r.stdout || r.stderr || 'log is empty');
  });
  $('btnReset').addEventListener('click', async () => {
    await exec(CLI + ' reset');
    localStorage.removeItem('fpshud-demo');
    await loadConfig();
    paint();
    toast('settings restored');
  });
}

function padText() {
  return $('padding_h').value + ' × ' + $('padding_v').value;
}

function show(text) {
  const c = $('console');
  c.hidden = false;
  c.textContent = (text || '').trim();
  c.scrollTop = 0;
}

/* ----------------------------------------------------------------- paint */

function paint() {
  $('enabled').checked = cfg.enabled === 'true';
  $('show_hz').checked = cfg.show_hz === 'true';
  $('dynamic_color').checked = cfg.dynamic_color === 'true';
  $('dynRow').hidden = cfg.dynamic_color !== 'true';
  $('enabledHint').textContent = cfg.enabled === 'true'
    ? 'overlay is drawn on top of everything'
    : 'overlay hidden — the service keeps running';

  const sel = (wrap, val, attr) => $(wrap).querySelectorAll('button').forEach((b) => {
    b.classList.toggle('sel', b.dataset[attr] === val);
  });
  sel('grid', cfg.position, 'pos');
  sel('decimals', cfg.decimals, 'val');
  sel('source', cfg.source === 'sysfs' ? 'auto' : cfg.source, 'val');
  sel('window_type', cfg.window_type, 'val');
  $('nodeRow').hidden = cfg.source !== 'node';

  const put = (id, v) => { if (document.activeElement !== $(id)) $(id).value = v; };
  put('x', cfg.x); $('xOut').textContent = cfg.x + ' dp';
  put('y', cfg.y); $('yOut').textContent = cfg.y + ' dp';
  put('text_size', cfg.text_size); $('text_sizeOut').textContent = cfg.text_size + ' sp';
  put('bg_radius', cfg.bg_radius); $('bg_radiusOut').textContent = cfg.bg_radius + ' dp';
  put('interval', cfg.interval); $('intervalOut').textContent = cfg.interval + ' ms';
  put('fps_period_ms', cfg.fps_period_ms);
  $('fps_period_msOut').textContent = +cfg.fps_period_ms === 0
    ? 'kernel default' : cfg.fps_period_ms + ' ms';
  put('padding_h', cfg.padding_h);
  put('padding_v', cfg.padding_v);
  $('padOut').textContent = padText();
  put('opacity', Math.round(parseFloat(cfg.opacity || '1') * 100));
  $('opacityOut').textContent = Math.round(parseFloat(cfg.opacity || '1') * 100) + '%';
  put('label', cfg.label === 'none' ? '' : cfg.label);
  put('text_color', rgbOf(cfg.text_color));
  put('color_good', rgbOf(cfg.color_good));
  put('color_ok', rgbOf(cfg.color_ok));
  put('color_bad', rgbOf(cfg.color_bad));
  put('bg_rgb', rgbOf(cfg.bg_color));
  put('bgAlpha', alphaOf(cfg.bg_color));
  $('bgAlphaOut').textContent = alphaOf(cfg.bg_color) + '%';

  paintPreview();
}

function paintPreview() {
  const hud = $('previewHud');
  const dec = parseInt(cfg.decimals || '0', 10);
  const label = cfg.label === 'none' ? '' : cfg.label;
  let txt = (label ? label + ' ' : '') + previewFps.toFixed(dec);
  if (cfg.show_hz === 'true') txt += '  120Hz';
  hud.textContent = txt;

  hud.style.color = cssColor(cfg.text_color);
  hud.style.background = cssColor(cfg.bg_color);
  hud.style.borderRadius = cfg.bg_radius + 'px';
  hud.style.padding = cfg.padding_v + 'px ' + cfg.padding_h + 'px';
  hud.style.fontSize = cfg.text_size + 'px';
  hud.style.opacity = cfg.opacity;
  hud.style.display = cfg.enabled === 'true' ? '' : 'none';

  const scale = 0.45;
  const x = (parseFloat(cfg.x) || 0) * scale;
  const y = (parseFloat(cfg.y) || 0) * scale;
  const pos = cfg.position || 'top_left';
  hud.style.top = hud.style.bottom = hud.style.left = hud.style.right = '';
  hud.style.transform = '';

  if (pos.startsWith('top')) hud.style.top = y + 'px';
  else if (pos.startsWith('bottom')) hud.style.bottom = y + 'px';
  else { hud.style.top = '50%'; hud.style.transform = 'translateY(-50%)'; }

  if (pos.endsWith('left')) hud.style.left = x + 'px';
  else if (pos.endsWith('right')) hud.style.right = x + 'px';
  else {
    hud.style.left = '50%';
    hud.style.transform = (hud.style.transform ? 'translateY(-50%) ' : '') + 'translateX(-50%)';
  }
}

/* ---------------------------------------------------------------- status */

async function refresh() {
  if (demo) {
    previewFps = 118 + Math.random() * 2;
    $('pill').textContent = 'demo';
    $('pill').className = 'pill off';
    $('liveFps').textContent = previewFps.toFixed(0);
    $('version').textContent = 'preview build';
    $('sourceHint').textContent = 'Diagnostics need root — open this page from the KernelSU manager.';
    paintPreview();
    return;
  }
  const { stdout } = await exec(CLI + ' status');
  let st = {};
  try { st = JSON.parse(stdout.trim().split('\n').pop()); } catch (e) { /* ignore */ }

  const running = st.running === true || st.running === 'true';
  $('pill').textContent = running ? 'running' : 'stopped';
  $('pill').className = 'pill ' + (running ? 'on' : 'off');
  $('liveFps').textContent = st.fps ? Math.round(parseFloat(st.fps)) : '--';
  if (st.fps) previewFps = parseFloat(st.fps);
  $('version').textContent = st.version || '';
  $('subtitle').textContent = running
    ? 'pid ' + st.pid + (st.node ? ' · kernel node' : ' · vsync counter')
    : 'service not running';
  $('sourceHint').textContent = st.node
    ? 'reading ' + st.node
    : 'no kernel fps node in use — counting vsyncs (matches the panel rate)';
  paintPreview();
}

async function loadNodes() {
  if (demo) return;
  const { stdout } = await exec(CLI + ' probe');
  const sel = $('node');
  sel.innerHTML = '';
  const found = [];
  stdout.split('\n').forEach((line) => {
    const m = line.match(/^\s+(\/\S+)\s*=/);
    if (m) found.push(m[1]);
  });
  if (cfg.node && found.indexOf(cfg.node) === -1) found.unshift(cfg.node);
  if (!found.length) {
    const o = document.createElement('option');
    o.textContent = 'no kernel node found';
    o.value = '';
    sel.appendChild(o);
    return;
  }
  found.forEach((path) => {
    const o = document.createElement('option');
    o.value = path;
    o.textContent = path;
    if (path === cfg.node) o.selected = true;
    sel.appendChild(o);
  });
}

/* ------------------------------------------------------------------ boot */

(async function main() {
  if (hasKsu && typeof ksu.enableEdgeToEdge === 'function') {
    try { ksu.enableEdgeToEdge(true); } catch (e) { /* ignore */ }
  }
  wire();
  await loadConfig();
  paint();
  await loadNodes();
  await refresh();
  setInterval(refresh, 2000);
})();
