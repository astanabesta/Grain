// v3 UI — Liquid Glass camera. Engine + LUT data in scope from inlined modules.

(function () {
  const $ = (id) => document.getElementById(id);

  // RECIPES_CORPUS is injected by build.py: 201 real recipes collected from the
  // open-fuji-recipes corpus (the Google Sheet the Fuji community maintains).
  // The 12 hand-written ones in RECIPES are the curated shelf.
  const LIB = (typeof RECIPES_CORPUS !== 'undefined' ? RECIPES_CORPUS : []).map((r) => ({
    ...r,
    note: r.creator ? `by ${r.creator}` : '',
    curated: false,
  }));
  const SHELF = RECIPES.map((r) => ({ ...r, curated: true }));
  const ALL = SHELF.concat(LIB);
  // Two stacked canvases. A canvas can only ever have ONE context type, and
  // asking for 2d first permanently locks out webgl2 — which is why the GPU
  // path silently fell back to the CPU engine. #viewport is WebGL only,
  // #viewport2d is the CPU fallback, and exactly one is visible at a time.
  const viewport = $('viewport');
  const viewport2d = $('viewport2d');
  let vctxCache = null;
  const vctx = () => {
    if (!vctxCache) vctxCache = viewport2d.getContext('2d', { willReadFrequently: true });
    return vctxCache;
  };
  const work = document.createElement('canvas');
  const wctx = work.getContext('2d', { willReadFrequently: true });

  const TOP_PAD = 74;
  const BOTTOM_PAD = 104;

  const ASPECTS = {
    '4:3': 4 / 3, '3:2': 3 / 2, '1:1': 1, '16:9': 16 / 9,
  };

  const state = {
    recipeId: SHELF[1].id,
    custom: null,
    exposure: 0,
    zoom: 1,
    aspect: '3:2',
    sceneKelvin: 5500,
    base: 'official',
    comparing: false,
    image: null,
    video: null,
    live: false,
    lutsLoaded: 0,
    lastShot: null,
    sheet: null,
    grid: false,
    histogram: true,
    level: false,
    timer: 0,
    tilt: 0,
    busy: false,
    guide: null,
    dpr: 1,
    camState: 'idle',
    rawMeta: null,      // idle | starting | live | denied | unavailable
    facing: 'environment', // environment | user
    lastStream: null,
  };

  // ── embedded LUTs ────────────────────────────────────────────
  function loadEmbeddedLUTs() {
    let n = 0;
    for (const slug of Object.values(OFFICIAL_SIMS)) {
      const b64 = LUT_DATA[slug];
      if (!b64) continue;
      const bin = atob(b64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      if (bytes.length !== LUT_SIZE ** 3 * 3) continue;
      LUT_CACHE.set(slug, bytes);
      n++;
    }
    return n;
  }

  // ── recipe helpers ───────────────────────────────────────────
  const baseRecipe = () => ALL.find((r) => r.id === state.recipeId) || SHELF[0];
  function activeRecipe() {
    const b = baseRecipe();
    return (state.custom && state.custom.id === b.id) ? state.custom : b;
  }
  function activeLUT(r) {
    if (state.base !== 'official' || !state.lutsLoaded) return null;
    return loadedSync(r.sim);
  }
  const simLabel = (r) => {
    const s = FILM_SIMS[r.sim] || {};
    const m = r.monoFilter && r.monoFilter !== 'none' ? ` +${r.monoFilter[0].toUpperCase()}` : '';
    return (s.label || r.sim) + m;
  };
  const wbLabel = (r) => typeof r.wb === 'number' ? `${r.wb}K`
    : ({ auto: 'Auto WB', autoWhite: 'AWB White', autoAmbience: 'AWB Ambience' }[r.wb] || r.wb);
  const sgn = (v) => { const n = Number(v); return n > 0 ? `+${n}` : `${n}`; };

  // ── layout: fit the chosen aspect inside the stage ───────────
  function sourceIsPortrait() {
    const el = state.live ? state.video : state.image;
    if (!el) return false;
    const w = state.live ? state.video.videoWidth : state.image.naturalWidth;
    const h = state.live ? state.video.videoHeight : state.image.naturalHeight;
    return w && h ? h > w : false;
  }

  function layout() {
    const stage = $('app').getBoundingClientRect();
    const W = Math.max(2, Math.round(stage.width));
    const H = Math.max(2, Math.round(stage.height));

    // Full-bleed viewfinder, like a phone camera: the whole screen shows the
    // picture with the recipe applied, and the aspect ratio is a crop guide on
    // top. Only the guide is saved.
    //
    // Preview budget. When the GPU path is active this can be a full screen
    // (the shader is pointwise); it only has to stay small for the CPU
    // fallback, which is what the 0.35 MP cap is for.
    const targetPx = state.gl ? 550000 : 200000;
    const dpr = Math.max(0.7, Math.min(2, Math.min(window.devicePixelRatio || 1,
                          Math.sqrt(targetPx / (W * H)))));
    viewport.style.width = '100%';
    viewport.style.height = '100%';
    viewport.width = Math.round(W * dpr);
    viewport.height = Math.round(H * dpr);
    state.dpr = dpr;

    // One rule for every aspect, applied to the guide and to the saved file:
    //   · portrait source (or a phone held upright on the camera) -> the ratio
    //     is rotated, because that is the shape you are framing
    //   · landscape source -> the ratio as written, never sliced into a strip
    // `1:1` is unaffected either way. The old code mixed these two rules and
    // produced a frame that did not match what was saved.
    const base = ASPECTS[state.aspect];
    if (!base) return;
    const portrait = sourceIsPortrait();
    const ar = portrait ? (1 / base) : base;

    const availW = W - 24;
    const availH = H - TOP_PAD - BOTTOM_PAD;
    let gw = availW, gh = availW / ar;
    if (gh > availH) { gh = availH; gw = availH * ar; }
    state.guide = {
      x: Math.round((W - gw) / 2),
      y: Math.round(TOP_PAD + (availH - gh) / 2),
      w: Math.round(gw),
      h: Math.round(gh),
    };

    const fg = $('frameGuide');
    if (fg) {
      fg.style.left = state.guide.x + 'px';
      fg.style.top = state.guide.y + 'px';
      fg.style.width = state.guide.w + 'px';
      fg.style.height = state.guide.h + 'px';
      fg.hidden = false;
    }
    $('frameText').textContent = state.aspect;
    // a live camera on a portrait phone frames rotated, so tell the user which
    // shape they will actually get rather than leaving them to guess
    const saved = state.guide.w >= state.guide.h ? 'landscape' : 'portrait';
    $('frameText').textContent = state.aspect;
    $('frameText').setAttribute('title', `${saved} ${state.guide.w}:${state.guide.h}`);
    renderView();
    drawThumbs();
  }

  // ── draw the current source into the working canvas ─────────
  // Render the framed region at an explicit pixel size. The source is
  // cover-cropped to the guide's ratio first, so we never sample a narrow strip
  // and blow it up — the crop always matches the frame you see.
  function captureRegion(W, H) {
    const srcEl = state.live ? state.video : state.image;
    if (!srcEl) return null;
    const sw = state.live ? state.video.videoWidth : state.image.naturalWidth;
    const sh = state.live ? state.video.videoHeight : state.image.naturalHeight;
    if (!sw || !sh || W < 2 || H < 2) return null;

    let cw = sw, ch = sh;
    if (sw / sh > W / H) { cw = sh * (W / H); ch = sh; }
    else { cw = sw; ch = sw * (H / W); }
    cw /= state.zoom; ch /= state.zoom;
    const cx = (sw - cw) / 2, cy = (sh - ch) / 2;

    if (work.width !== W || work.height !== H) { work.width = W; work.height = H; }
    wctx.drawImage(srcEl, cx, cy, cw, ch, 0, 0, W, H);
    return wctx.getImageData(0, 0, W, H);
  }


  function renderView() {
    if (!state.guide) return;
    const dpr = state.dpr || 1;
    const W = Math.round(viewport.width / dpr), H = Math.round(viewport.height / dpr);
    const srcEl = state.live ? state.video : state.image;
    if (!srcEl) return;

    // ── GPU ──────────────────────────────────────────────────────────────
    if (initGL()) {
      viewport.hidden = false;
      if (vctxCache) viewport2d.style.display = 'none';
      const r = activeRecipe();
      const g = state.guide;
      glr.draw(srcEl, r, {
        gains: wbGains(r, state.sceneKelvin),
        exposure: state.exposure,
        lut: state.comparing ? null : activeLUT(r),
        lutSlug: state.comparing ? null : r.sim,
        mono: state.comparing ? false : (FILM_SIMS[r.sim] || {}).mono,
        monoFilter: state.comparing ? 'none' : (r.mono || r.monoFilter || 'none'),
        preview: state.live,
      });
      if (state.comparing) {
        // compare shows the untouched frame: re-draw with the identity recipe
        // would cost another pass, so the CPU path handles compare instead
      }
      if (state.histogram) histMaybe();
      return;
    }

    // ── CPU fallback ─────────────────────────────────────────────────────
    viewport.hidden = true;
    viewport2d.style.display = 'block';
    viewport2d.style.width = '100%';
    viewport2d.style.height = '100%';
    viewport2d.width = viewport.width;
    viewport2d.height = viewport.height;
    vctx().setTransform(1, 0, 0, 1, 0, 0);
    vctx().clearRect(0, 0, viewport.width, viewport.height);
    vctx().setTransform(dpr, 0, 0, dpr, 0, 0);

    const img = captureRegion(Math.round(W * dpr), Math.round(H * dpr));
    if (!img) return;

    if (state.comparing) {
      wctx.putImageData(img, 0, 0);
      drawHist(img);
    } else {
      const r = activeRecipe();
      const out = wctx.createImageData(img.width, img.height);
      render(img, out, r, {
        sceneKelvin: state.sceneKelvin,
        exposure: state.exposure,
        grainSeed: 7 + (state.live ? (performance.now() / 120 | 0) % 4 : 0),
        lut: activeLUT(r),
      });
      wctx.putImageData(out, 0, 0);
      drawHist(out);
    }
    vctx().drawImage(work, 0, 0, img.width, img.height, 0, 0, W, H);
  }

  // Sampling pixels back off a WebGL canvas with readPixels is slow, so the
  // histogram is built from a deliberately tiny downscale instead.
  let histCanvas = null;
  let histLast = 0;
  // Reading pixels back off a WebGL canvas stalls the pipeline, so it runs about
  // six times a second instead of sixty. A histogram is a coarse instrument; it
  // does not need to keep up with the viewfinder.
  function histMaybe() {
    const now = performance.now();
    if (now - histLast < 160) return;
    histLast = now;
    histFromCanvas();
  }

  function histFromCanvas() {
    if (!state.histogram) { $('hist').hidden = true; return; }
    if (!histCanvas) {
      histCanvas = document.createElement('canvas');
      histCanvas.width = 132; histCanvas.height = 88;
    }
    const hx = histCanvas.getContext('2d', { willReadFrequently: true });
    try {
      hx.drawImage(viewport, 0, 0, 132, 88);
    } catch (e) { return; }
    drawHist(hx.getImageData(0, 0, 132, 88));
  }

  // ══ GPU path ═══════════════════════════════════════════════════
  // The CPU engine renders ~9 fps. The shader does the same pointwise maths at
  // screen rate, so this is what makes the viewfinder feel like a camera.
  let glr = null;
  function initGL() {
    if (glr !== null) return glr;
    try {
      glr = createGLRenderer(viewport) || false;
      // canvas context is exclusive: the 2D path loses drawing rights here
      if (glr) state.gl = true;
    } catch (e) { glr = false; }
    return glr;
  }

  // ── histogram ───────────────────────────────────────────────
  function drawHist(imageData) {
    const c = $('hist');
    if (!state.histogram) { c.hidden = true; return; }
    c.hidden = false;
    const hx = c.getContext('2d');
    const bins = 48;
    const R = new Float32Array(bins), G = new Float32Array(bins), B = new Float32Array(bins);
    const d = imageData.data;
    const n = d.length / 4;
    const step = Math.max(4, ((n / 3000) | 0) * 4);
    for (let i = 0; i < d.length; i += step) {
      R[(d[i] / 255 * (bins - 1)) | 0]++;
      G[(d[i + 1] / 255 * (bins - 1)) | 0]++;
      B[(d[i + 2] / 255 * (bins - 1)) | 0]++;
    }
    // A large black or blown region puts almost every pixel in one bin, and
    // normalising to that spike flattens the whole curve into the floor. Ignore
    // the two end bins when finding the peak, then clamp.
    let mx = 1;
    for (let i = 1; i < bins - 1; i++) mx = Math.max(mx, R[i], G[i], B[i]);

    const w = c.width, h = c.height;
    hx.clearRect(0, 0, w, h);

    // additive blending, so overlapping channels read as white where they stack
    hx.globalCompositeOperation = 'lighter';
    const draw = (arr, color) => {
      hx.beginPath();
      hx.moveTo(0, h);
      for (let i = 0; i < bins; i++) {
        const x = (i / (bins - 1)) * w;
        const y = h - Math.pow(Math.min(1, arr[i] / mx), 0.42) * h * 0.94;
        hx.lineTo(x, y);
      }
      hx.lineTo(w, h);
      hx.closePath();
      hx.fillStyle = color;
      hx.fill();
    };
    draw(R, 'rgba(255,74,74,.62)');
    draw(G, 'rgba(74,235,120,.62)');
    draw(B, 'rgba(90,150,255,.66)');
    hx.globalCompositeOperation = 'source-over';
  }

  let rt = null;
  function requestRender() {
    clearTimeout(rt);
    rt = setTimeout(renderView, 20);
  }

  // ── live loop (throttled; CPU rendering is ~150-250ms) ──────
  let liveTimer = null;

  function startLive() {
    if (state.live) return;
    state.live = true;
    const tick = () => {
      if (!state.live) return;
      renderView();
      liveTimer = setTimeout(tick, 40);
    };
    tick();
    setMode(state.camState === 'live' ? 'LIVE' : 'SAMPLE', false);
    updateShutterState();
  }

  // ══ camera ════════════════════════════════════════════════════
  // Everything a phone camera needs before getUserMedia will hand over a
  // stream: a secure context, a declared API, a user gesture on the first
  // call, and (in a WebView shell) a native permission grant. Each failure has
  // its own name, and they need different answers — a single "camera failed"
  // toast sends people to a settings screen that cannot help them.
  function cameraSupport() {
    if (!window.isSecureContext) return 'insecure';
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return 'unsupported';
    return 'ok';
  }

  async function openCamera(opts = {}) {
    const support = cameraSupport();
    if (support !== 'ok') {
      state.camState = 'unavailable';
      toast(support === 'insecure'
        ? 'Camera needs HTTPS — use "Load a photo" instead'
        : 'This browser has no camera API — use "Load a photo"', 4200);
      updateShutterState();
      return false;
    }
    if (state.camState === 'starting') return false;
    state.camState = 'starting';
    updateShutterState();

    const wantFacing = opts.facing || state.facing || 'environment';
    const attempts = [
      { video: { facingMode: { exact: wantFacing }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false },
      { video: { facingMode: wantFacing }, audio: false },
      { video: true, audio: false },
    ];

    let lastErr = null;
    for (const constraints of attempts) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia(constraints);
        const v = $('video');
        state.video = v;            // the render path reads this, not the DOM
        v.srcObject = stream;
        v.setAttribute('playsinline', '');
        v.muted = true;
        await v.play();
        // some devices report 0x0 until the first frame arrives
        await new Promise((res) => {
          if (v.videoWidth) return res();
          const done = () => res();
          v.addEventListener('loadedmetadata', done, { once: true });
          setTimeout(done, 1500);
        });
        state.lastStream = stream;
        state.facing = wantFacing;
        state.camState = 'live';
        startLive();
        toast('Camera live');
        buildMenu();
        return true;
      } catch (err) {
        lastErr = err;
        // Only a "constraint" failure is worth retrying with looser constraints.
        // A refused permission will be refused again.
        const n = err && err.name;
        if (n === 'OverconstrainedError' || n === 'NotFoundError') continue;
        break;
      }
    }

    state.camState = lastErr && lastErr.name === 'NotAllowedError' ? 'denied' : 'unavailable';
    toast(cameraHelp(lastErr), 5200);
    updateShutterState();
    buildMenu();
    return false;
  }

  function stopLive() {
    state.live = false;
    clearTimeout(liveTimer);
    if (state.video && state.video.srcObject) {
      state.video.srcObject.getTracks().forEach((t) => t.stop());
      state.video.srcObject = null;
    }
    state.lastStream = null;
    if (state.camState === 'live') state.camState = 'idle';
    updateShutterState();
  }

  function cameraHelp(err) {
    const n = err && err.name;
    if (n === 'NotAllowedError') {
      return 'Camera blocked. Check the app permission in system settings, then reopen — or use "Load a photo"';
    }
    if (n === 'NotFoundError' || n === 'OverconstrainedError') {
      return 'No camera matched — try "Load a photo"';
    }
    if (n === 'NotReadableError') {
      return 'Camera is busy in another app — close it and try again';
    }
    return 'Camera unavailable — use "Load a photo"';
  }

  // The shutter must never look ready when it cannot fire.
  function updateShutterState() {
    const btn = $('btnShoot');
    if (!btn) return;
    const ready = state.camState === 'live' || !!state.image;
    btn.classList.toggle('disabled', !ready);
    btn.setAttribute('aria-disabled', String(!ready));
  }

  function setMode(text, sample) {
    $('modeText').textContent = text;
    $('modeDot').className = 'dot' + (sample ? ' sample' : '');
  }

  // ── exposure rail ───────────────────────────────────────────
  function setExposure(v) {
    state.exposure = Math.max(-2, Math.min(2, Math.round(v * 3) / 3));
    const pct = (state.exposure + 2) / 4;
    const thumb = $('evThumb');
    const track = $('evTrack');
    const top = track.offsetTop ?? 20;
    const h = track.offsetHeight || 100;
    const railH = $('evRail').offsetHeight || 200;
    thumb.style.top = `${top + (1 - pct) * h}px`;
    const fill = $('evFill');
    if (pct >= .5) { fill.style.bottom = '50%'; fill.style.height = `${(pct - .5) * 100}%`; }
    else { fill.style.bottom = `${pct * 100}%`; fill.style.height = `${(.5 - pct) * 100}%`; }
    $('evVal').textContent = (state.exposure > 0 ? '+' : '') + state.exposure.toFixed(1);
    requestRender();
  }

  function wireExposure() {
    const rail = $('evRail');
    const track = $('evTrack');
    let drag = false;
    const apply = (e) => {
      const rect = track.getBoundingClientRect();
      const y = (e.touches ? e.touches[0].clientY : e.clientY) - rect.top;
      const pct = 1 - Math.max(0, Math.min(1, y / rect.height));
      setExposure(pct * 4 - 2);
    };
    rail.addEventListener('pointerdown', (e) => {
      drag = true;
      rail.classList.add('lift');
      rail.setPointerCapture(e.pointerId);
      apply(e);
    });
    rail.addEventListener('pointermove', (e) => { if (drag) apply(e); });
    const end = () => { drag = false; rail.classList.remove('lift'); };
    rail.addEventListener('pointerup', end);
    rail.addEventListener('pointercancel', end);
    rail.addEventListener('touchmove', (e) => e.preventDefault(), { passive: false });
  }

  // ── zoom ────────────────────────────────────────────────────
  function setZoom(z) {
    state.zoom = z;
    for (const b of document.querySelectorAll('.zoom-btn')) {
      b.classList.toggle('on', Number(b.dataset.zoom) === z);
    }
    renderView();
  }

  // ── recipe picker ───────────────────────────────────────────
  let thumbTimer = null;
  function scheduleThumbs() {
    clearTimeout(thumbTimer);
    thumbTimer = setTimeout(drawThumbs, 220);
  }

  function drawThumbs() {
    if (!state.image && !state.live) return;
    if (!document.querySelector('.rcard')) return;
    const TW = 96, TH = 72;
    const t = document.createElement('canvas');
    t.width = TW; t.height = TH;
    const tc = t.getContext('2d', { willReadFrequently: true });
    const srcEl = state.live ? state.video : state.image;
    const sw = state.live ? state.video.videoWidth : state.image.naturalWidth;
    const sh = state.live ? state.video.videoHeight : state.image.naturalHeight;
    if (!sw || !sh) return;
    const crop = Math.min(sw / TW, sh / TH);
    tc.drawImage(srcEl, (sw - TW * crop) / 2, (sh - TH * crop) / 2, TW * crop, TH * crop, 0, 0, TW, TH);
    const base = tc.getImageData(0, 0, TW, TH);
    const out = tc.createImageData(TW, TH);
    const tmp = document.createElement('canvas');
    tmp.width = TW; tmp.height = TH;
    const tmx = tmp.getContext('2d');
    for (const r of visibleRecipes()) {
      const rec = (state.custom && state.custom.id === r.id) ? state.custom : r;
      render(base, out, rec, { sceneKelvin: state.sceneKelvin, grainSeed: 2, lut: activeLUT(rec) });
      tmx.putImageData(out, 0, 0);
      const el = document.querySelector(`.rcard[data-id="${r.id}"] .rcard-img`);
      if (el) el.style.backgroundImage = `url(${tmp.toDataURL('image/jpeg', 0.74)})`;
    }
  }

  // The picker has to stay usable with 201 recipes: a search field and a row of
  // simulation filters, and only the visible cards get thumbnails rendered.
  let query = '';
  let simFilter = 'all';

  function visibleRecipes() {
    const q = query.trim().toLowerCase();
    return ALL.filter((r) => {
      if (simFilter !== 'all' && r.sim !== simFilter) return false;
      if (!q) return true;
      return (r.name + ' ' + (r.creator || '') + ' ' + simLabel(r)).toLowerCase().includes(q);
    });
  }

  function buildRecipeGrid() {
    const host = $('rgrid');
    host.innerHTML = '';

    // filter chips
    const bar = document.createElement('div');
    bar.className = 'rfilter';
    const sims = [...new Set(ALL.map((r) => r.sim))];
    bar.innerHTML =
      `<input class="rsearch" id="rSearch" type="search" placeholder="Search ${ALL.length} recipes…" ` +
      `value="${query.replace(/"/g, '&quot;')}" />` +
      `<div class="rchips" id="rChips">` +
      `<button data-sim="all" class="${simFilter === 'all' ? 'on' : ''}">All</button>` +
      sims.map((s2) => `<button data-sim="${s2}" class="${simFilter === s2 ? 'on' : ''}">` +
        `${(FILM_SIMS[s2] || {}).label || s2}</button>`).join('') +
      `</div>`;
    host.appendChild(bar);

    const grid = document.createElement('div');
    grid.className = 'rgrid-inner';
    host.appendChild(grid);

    const list = visibleRecipes();
    for (const r of list) {
      const b = document.createElement('button');
      b.className = 'rcard';
      b.dataset.id = r.id;
      b.setAttribute('aria-selected', String(r.id === state.recipeId));
      b.innerHTML = `<div class="rcard-img"></div>` +
        `<div class="rcard-name">${r.name}</div>` +
        `<div class="rcard-sub">${r.curated ? 'Curated' : (r.creator || simLabel(r))}</div>`;
      b.addEventListener('click', () => { chooseRecipe(r.id); closeSheets(); });
      grid.appendChild(b);
    }
    if (!list.length) {
      const p = document.createElement('p');
      p.className = 'fineprint';
      p.textContent = 'No recipe matches that.';
      grid.appendChild(p);
    }

    const r0 = activeRecipe();
    $('recipeFine').textContent = `${list.length} shown — ${r0.note || ''}`;

    $('rSearch').addEventListener('input', (e) => {
      query = e.target.value;
      const sel = document.activeElement === e.target;
      buildRecipeGrid();
      if (sel) { const f = $('rSearch'); f.focus(); f.setSelectionRange(f.value.length, f.value.length); }
    });
    $('rChips').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      simFilter = b.dataset.sim;
      buildRecipeGrid();
    });

    scheduleThumbs();
  }

  let SEEK = ALL;

  function chooseRecipe(id) {
    if (id === state.recipeId) return;
    state.recipeId = id;
    state.custom = null;
    // the id may come from the 201-recipe corpus, not the curated shelf
    $('rbName').textContent = (ALL.find((r) => r.id === id) || {}).name || '—';
    SEEK = visibleRecipes();
    for (const c of document.querySelectorAll('.rcard')) {
      c.setAttribute('aria-selected', String(c.dataset.id === id));
    }
    renderView();
    scheduleThumbs();
    if (state.sheet === 'tune') buildTune();
  }

  // ── tune ────────────────────────────────────────────────────
  const SIM_OPTS = Object.entries(FILM_SIMS).map(([k, v]) => [k, v.label]);
  const WB_OPTS = [
    ['auto', 'Auto'], ['autoWhite', 'Auto White Priority'], ['autoAmbience', 'Auto Ambience Priority'],
    ['daylight', 'Daylight 5500K'], ['shade', 'Shade 7000K'], ['incandescent', 'Incandescent 3200K'],
    ['fluorescent1', 'Fluorescent 1 4000K'], ['fluorescent3', 'Fluorescent 3 5000K'],
    ['3000', '3000K'], ['4000', '4000K'], ['5200', '5200K'], ['5600', '5600K'],
    ['5900', '5900K'], ['7100', '7100K'],
  ];
  const THREE = [['off', 'Off'], ['weak', 'Weak'], ['strong', 'Strong']];

  const GROUPS = [
    ['Base', [['sim', 'Simulation', 'select', SIM_OPTS]]],
    ['Tone', [
      ['highlight', 'Highlight', 'range', -4, 4, 0.5],
      ['shadow', 'Shadow', 'range', -4, 4, 0.5],
      ['dynamicRange', 'Dynamic range', 'seg',
        [['100', 'DR100'], ['200', 'DR200'], ['400', 'DR400'], ['auto', 'Auto']]],
    ]],
    ['Colour', [
      ['color', 'Color', 'range', -4, 4, 1],
      ['colorChrome', 'Color Chrome', 'seg', THREE],
      ['colorChromeBlue', 'Chrome FX Blue', 'seg', THREE],
    ]],
    ['White balance', [
      ['wb', 'Balance', 'select', WB_OPTS],
      ['wbShiftRed', 'Shift R', 'range', -9, 9, 1],
      ['wbShiftBlue', 'Shift B', 'range', -9, 9, 1],
    ]],
    ['Texture', [
      ['sharpness', 'Sharpness', 'range', -4, 4, 1],
      ['clarity', 'Clarity', 'range', -5, 5, 1],
      ['grain', 'Grain', 'seg', THREE],
      ['grainSize', 'Grain size', 'seg', [['small', 'Small'], ['large', 'Large']]],
    ]],
    ['Exposure', [['ev', 'Recipe EV', 'range', -2, 2, 0.33]]],
  ];


  // ══ the glass slider ═══════════════════════════════════════════
  // A native <input type=range> cannot do any of this: its thumb is drawn by
  // the platform and cannot refract, stretch or spring. So this is a custom
  // pointer-driven track where the thumb is a real element that lifts into a
  // lens while held, squashes with drag velocity, and springs back on release.
  function wireSlider(host, key, min, max, step, initial) {
    const track = host.querySelector('.slider-track');
    const fill = host.querySelector('.slider-fill');
    const thumb = host.querySelector('.slider-thumb');
    const out = host.querySelector('output');

    let value = Number(initial);
    let dragging = false;
    let lastVal = value;
    let lastT = 0;
    let raf = 0;

    const fmt = (v) => (key === 'ev' ? v.toFixed(2) : sgn(v));
    // Snap on an integer step *index* rather than by adding floats. A step that
    // does not divide the range evenly (EV: 4 / 0.33 = 12.12) otherwise stops
    // short of the ends and misses zero, so a 1/3-stop slider reads "+1.96" at
    // full travel and "−0.02" at centre. Spreading the steps evenly fixes both.
    const steps = Math.max(1, Math.round((max - min) / step));
    const unit = (max - min) / steps;
    const snap = (v) => {
      const idx = Math.max(0, Math.min(steps, Math.round((v - min) / (max - min) * steps)));
      return Math.round((min + idx * unit) * 1000) / 1000;
    };
    const pct = (v) => (v - min) / (max - min);

    function paint(v, animate) {
      const p = pct(v);
      thumb.style.transition = animate ? '' : 'none';
      thumb.style.left = `calc(${p * 100}% )`;
      fill.style.width = `calc(${p * 100}%)`;
      out.textContent = fmt(v);
      track.setAttribute('aria-valuenow', String(v));
      if (!animate) requestAnimationFrame(() => { thumb.style.transition = ''; });
    }

    paint(value, false);

    // keyboard: the control is focusable and behaves like a real slider
    track.addEventListener('keydown', (e) => {
      const big = steps > 16 ? 4 : 2;
      let d = 0;
      if (e.key === 'ArrowRight' || e.key === 'ArrowUp') d = unit;
      if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') d = -unit;
      if (e.key === 'PageUp') d = unit * big;
      if (e.key === 'PageDown') d = -unit * big;
      if (e.key === 'Home') { set(min); return; }
      if (e.key === 'End') { set(max); return; }
      if (!d) return;
      e.preventDefault();
      set(snap(value + d));
    });

    function set(v) {
      value = snap(v);
      paint(value, true);
      edit({ [key]: value });
    }

    function fromEvent(e) {
      const rect = track.getBoundingClientRect();
      const x = (e.touches ? e.touches[0].clientX : e.clientX) - rect.left;
      const p = Math.max(0, Math.min(1, x / rect.width));
      const v = snap(min + p * (max - min));

      // velocity -> squash and stretch, the tactile bit
      const now = performance.now();
      const dt = Math.max(8, now - lastT);
      const vel = (v - lastVal) / dt;              // units per ms
      lastVal = v;
      lastT = now;
      // Normalise velocity by the slider's own range, so a fast flick on a
      // narrow control deforms as much as one on a wide control. A full sweep
      // in ~0.5 s reads as the top of the range.
      const s = Math.min(0.85, (Math.abs(vel) / (max - min || 1)) * 320);
      thumb.style.transform =
        `scale(${(1.34 + s * 0.34).toFixed(3)}, ${(1.34 - s * 0.20).toFixed(3)})`;

      value = v;
      paint(value, false);
      out.textContent = fmt(value);
      track.setAttribute('aria-valuenow', String(value));
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => edit({ [key]: value }));
    }

    track.addEventListener('pointerdown', (e) => {
      dragging = true;
      lastVal = value;
      lastT = performance.now();
      host.classList.add('dragging');
      thumb.style.transition = 'none';
      thumb.style.transform = 'scale(1.34, 1.34)';
      track.setPointerCapture(e.pointerId);
      fromEvent(e);
    });
    track.addEventListener('pointermove', (e) => { if (dragging) fromEvent(e); });
    const end = () => {
      if (!dragging) return;
      dragging = false;
      host.classList.remove('dragging');
      // release the inline transform so the spring transition takes over
      thumb.style.transform = '';
      thumb.style.transition = '';
    };
    track.addEventListener('pointerup', end);
    track.addEventListener('pointercancel', end);
    track.addEventListener('touchmove', (e) => { if (dragging) e.preventDefault(); }, { passive: false });
  }

  // the segmented control's pill slides between options
  function positionPill(seg) {
    const pill = seg.querySelector('.seg-pill');
    const on = [...seg.children].find((c) => c.classList && c.classList.contains('on'));
    if (!pill || !on || !on.offsetWidth) return;
    pill.style.width = on.offsetWidth + 'px';
    pill.style.transform = `translateX(${on.offsetLeft - 3}px)`;
  }

  function buildTune() {
    const r = activeRecipe();
    $('tuneTitle').textContent = r.name;
    const host = $('tuneBody');
    host.innerHTML = '';

    for (const [groupName, items] of GROUPS) {
      const card = document.createElement('div');
      card.className = 'card';
      const t = document.createElement('div');
      t.className = 'card-title';
      t.textContent = groupName;
      card.appendChild(t);

      for (const item of items) {
        const [key, label, kind] = item;
        const ctl = document.createElement('div');
        ctl.className = 'ctl';

        if (kind === 'range') {
          const [, , , min, max, step] = item;
          ctl.className = 'slider';
          ctl.innerHTML =
            `<div class="slider-head"><label for="c_${key}">${label}</label>` +
            `<output>${key === 'ev' ? Number(r[key]).toFixed(2) : sgn(r[key])}</output></div>` +
            `<div class="slider-track" id="c_${key}" role="slider" tabindex="0" ` +
            `aria-label="${label}" aria-valuemin="${min}" aria-valuemax="${max}" ` +
            `aria-valuenow="${r[key]}">` +
            `<div class="slider-fill"></div>` +
            `<div class="slider-thumb"><span class="rim"></span></div>` +
            `</div>`;
          card.appendChild(ctl);
          wireSlider(ctl, key, min, max, step, r[key]);
        } else if (kind === 'seg') {
          const opts = item[3];
          ctl.innerHTML = `<div class="slider-head"><label>${label}</label></div>` +
            `<div class="seg" data-key="${key}">` +
            `<span class="seg-pill"></span>` +
            opts.map(([v, l]) => `<button data-v="${v}">${l}</button>`).join('') + `</div>`;
          card.appendChild(ctl);
          const seg = ctl.querySelector('.seg');
          const mark = (val) => {
            for (const b of seg.children) {
              if (b.tagName === 'BUTTON') b.classList.toggle('on', b.dataset.v === String(val));
            }
            requestAnimationFrame(() => positionPill(seg));
          };
          mark(r[key]);
          // lift into a lens while the finger is down, settle on release
          seg.addEventListener('pointerdown', () => seg.classList.add('lifting'));
          const drop = () => seg.classList.remove('lifting');
          seg.addEventListener('pointerup', drop);
          seg.addEventListener('pointercancel', drop);
          seg.addEventListener('pointerleave', drop);
          seg.addEventListener('click', (e) => {
            const b = e.target.closest('button');
            if (!b) return;
            let v = b.dataset.v;
            if (key === 'dynamicRange' && v !== 'auto') v = Number(v);
            mark(v);
            edit({ [key]: v });
          });
        } else {
          const opts = item[3];
          ctl.innerHTML = `<div class="ctl-top"><label for="c_${key}">${label}</label></div>` +
            `<select id="c_${key}">` + opts.map(([v, l]) => `<option value="${v}">${l}</option>`).join('') + `</select>`;
          card.appendChild(ctl);
          // the card is not in the document yet, so getElementById would return
          // null here — resolve the control through its own subtree instead
          const sel = ctl.querySelector('select');
          sel.value = String(r[key]);
          sel.addEventListener('change', (e) => {
            let v = e.target.value;
            if (key === 'wb' && /^\d+$/.test(v)) v = Number(v);
            edit({ [key]: v });
          });
        }
      }
      host.appendChild(card);
    }

    const pre = document.createElement('pre');
    pre.className = 'recipe';
    pre.id = 'recipeText';
    pre.textContent = recipeToText(r);
    host.appendChild(pre);

    const row = document.createElement('div');
    row.className = 'btnrow';
    row.innerHTML = `<button class="btn" id="btnCopy">Copy recipe</button><button class="btn" id="btnReset">Reset</button>`;
    host.appendChild(row);

    $('btnCopy').addEventListener('click', copyRecipe);
    $('btnReset').addEventListener('click', () => {
      state.custom = null;
      buildTune();
      $('rbName').textContent = baseRecipe().name;
      renderView(); scheduleThumbs();
      toast('Recipe reset');
    });
  }

  function edit(patch) {
    const b = baseRecipe();
    const cur = (state.custom && state.custom.id === b.id) ? state.custom : b;
    state.custom = {
      ...cur, ...patch, id: b.id,
      name: cur.name.replace(/ \(edited\)$/, '') + ' (edited)',
    };
    $('tuneTitle').textContent = state.custom.name;
    $('rbName').textContent = state.custom.name;
    renderView(); scheduleThumbs();
    const pre = $('recipeText');
    if (pre) pre.textContent = recipeToText(state.custom);
  }

  async function copyRecipe() {
    const text = recipeToText(activeRecipe());
    try { await navigator.clipboard.writeText(text); toast('Recipe copied'); }
    catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); toast('Recipe copied'); }
      catch { toast('Copy blocked'); }
      ta.remove();
    }
  }

  // ── camera menu ─────────────────────────────────────────────
  function buildMenu() {
    const host = $('menuBody');
    host.innerHTML = '';

    const card = (title) => {
      const d = document.createElement('div');
      d.className = 'card';
      const t = document.createElement('div');
      t.className = 'card-title';
      t.textContent = title;
      d.appendChild(t);
      host.appendChild(d);
      return d;
    };

    // camera source
    const c1 = card('Source');
    const stateText = {
      live: 'Live camera',
      starting: 'Starting…',
      idle: 'Camera off',
      denied: 'Camera blocked',
      unavailable: 'No camera here',
    }[state.camState] || 'Camera';

    const swRow = document.createElement('div');
    swRow.className = 'switch-row';
    swRow.innerHTML = `<span>${stateText}</span>` +
      `<div class="switch${state.live ? ' on' : ''}" id="swLive"></div>`;
    c1.appendChild(swRow);
    $('swLive').addEventListener('click', async () => {
      if (state.live) { stopLive(); $('swLive').classList.remove('on'); }
      else {
        const ok = await openCamera();
        $('swLive').classList.toggle('on', ok);
        // the label carries the state; rebuild so it is never stale
        if (ok) buildMenu();
      }
    });

    // front / back, the one control every camera has
    if (state.live || state.camState === 'live') {
      const flipRow = document.createElement('div');
      flipRow.className = 'ctl';
      flipRow.innerHTML = `<div class="ctl-top"><label>Lens</label></div>` +
        `<div class="seg" id="segFacing">` +
        `<button data-v="environment">Back</button><button data-v="user">Front</button></div>`;
      c1.appendChild(flipRow);
      const mark = () => {
        for (const b of $('segFacing').children) b.classList.toggle('on', b.dataset.v === state.facing);
      };
      mark();
      $('segFacing').addEventListener('click', async (e) => {
        const b = e.target.closest('button');
        if (!b || b.dataset.v === state.facing) return;
        stopLive();
        const ok = await openCamera({ facing: b.dataset.v });
        if (ok) buildMenu();
      });
    }

    const photoRow = document.createElement('div');
    photoRow.className = 'ctl';
    photoRow.innerHTML = `<div class="ctl-top"><label>Load a photo</label></div>` +
      `<button class="btn" id="mPhoto" style="margin-top:6px">Choose image…</button>`;
    c1.appendChild(photoRow);
    $('mPhoto').addEventListener('click', () => $('file').click());

    // aspect
    const c2 = card('Frame');
    const arRow = document.createElement('div');
    arRow.className = 'ctl';
    arRow.innerHTML = `<div class="ctl-top"><label>Aspect ratio</label></div><div class="seg" id="segAspect">` +
      Object.keys(ASPECTS).map((k) => `<button data-v="${k}">${k}</button>`).join('') + `</div>`;
    c2.appendChild(arRow);
    const markAspect = () => {
      for (const b of $('segAspect').children) b.classList.toggle('on', b.dataset.v === state.aspect);
    };
    markAspect();
    $('segAspect').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      state.aspect = b.dataset.v;
      markAspect();
      layout();
    });

    // overlays
    const c3 = card('Overlays');
    const mkSwitch = (label, key, after) => {
      const row = document.createElement('div');
      row.className = 'switch-row';
      row.innerHTML = `<span>${label}</span><div class="switch${state[key] ? ' on' : ''}" data-k="${key}"></div>`;
      c3.appendChild(row);
      const sw = row.querySelector('.switch');
      sw.addEventListener('pointerdown', () => sw.classList.add('pressing'));
      const rel = () => sw.classList.remove('pressing');
      sw.addEventListener('pointerup', rel);
      sw.addEventListener('pointercancel', rel);
      sw.addEventListener('pointerleave', rel);
      sw.addEventListener('click', (e) => {
        state[key] = !state[key];
        e.currentTarget.classList.toggle('on', state[key]);
        if (after) after();
      });
    };
    mkSwitch('Rule-of-thirds grid', 'grid', () => { $('grid').classList.toggle('on', state.grid); });
    mkSwitch('Histogram', 'histogram', () => { $('hist').hidden = !state.histogram; renderView(); });
    mkSwitch('Level', 'level', () => { syncLevel(); });

    // timer
    const c4 = card('Timer');
    const tRow = document.createElement('div');
    tRow.className = 'ctl';
    tRow.innerHTML = `<div class="ctl-top"><label>Shutter delay</label></div><div class="seg" id="segTimer">` +
      `<button data-v="0">Off</button><button data-v="3">3s</button><button data-v="10">10s</button></div>`;
    c4.appendChild(tRow);
    const markTimer = () => {
      for (const b of $('segTimer').children) b.classList.toggle('on', Number(b.dataset.v) === state.timer);
    };
    markTimer();
    $('segTimer').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      state.timer = Number(b.dataset.v);
      markTimer();
    });

    // colour base
    const c5 = card('Colour base');
    const bRow = document.createElement('div');
    bRow.className = 'ctl';
    bRow.innerHTML = `<div class="ctl-top"><label>Simulation source</label></div><div class="seg" id="segBase">` +
      `<button data-v="official">Fuji LUT</button><button data-v="model">Model</button></div>`;
    c5.appendChild(bRow);
    const markBase = () => {
      for (const b of $('segBase').children) b.classList.toggle('on', b.dataset.v === state.base);
    };
    markBase();
    $('segBase').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      state.base = b.dataset.v;
      markBase();
      renderView(); scheduleThumbs();
      toast(state.base === 'official' ? 'Fujifilm official LUT' : 'Engine model');
    });

    // about
    const c6 = card('About');
    const about = document.createElement('div');
    about.className = 'ctl';
    about.innerHTML = `<button class="btn" id="mAbout">How this works</button>`;
    c6.appendChild(about);
    $('mAbout').addEventListener('click', () => { openSheet('info'); });
  }

  // ── level (device tilt) ─────────────────────────────────────
  function syncLevel() {
    $('level').hidden = !state.level;
    if (state.level && !syncLevel.bound) {
      syncLevel.bound = true;
      window.addEventListener('deviceorientation', (e) => {
        if (e.gamma == null) return;
        state.tilt = e.gamma;
        const line = $('levelLine');
        line.style.transform = `rotate(${Math.max(-12, Math.min(12, e.gamma))}deg)`;
        line.style.background = Math.abs(e.gamma) < 1.2
          ? 'rgba(126,240,168,.95)' : 'rgba(255,255,255,.75)';
      });
      // if the browser never delivers events (desktop), fall back to flat
      setTimeout(() => {
        if (!$('level').hidden) {
          $('levelLine').style.background = 'rgba(126,240,168,.95)';
        }
      }, 400);
    }
  }

  // ── shooting ────────────────────────────────────────────────
  function shoot() {
    if (!state.live && !state.image) {
      // no stream and no photo: say so, and offer the one action that helps
      toast(state.camState === 'denied'
        ? 'Camera blocked — use "Load a photo" to shoot this recipe'
        : 'Starting the camera…', 3600);
      openCamera();
      return;
    }
    if (state.timer > 0) return countdownThenShoot();
    fire();
  }

  function countdownThenShoot() {
    let n = state.timer;
    $('countdown').hidden = false;
    $('countText').textContent = n;
    const tick = () => {
      n--;
      if (n <= 0) {
        $('countdown').hidden = true;
        fire();
        return;
      }
      $('countText').textContent = n;
      $('countText').style.animation = 'none';
      requestAnimationFrame(() => { $('countText').style.animation = ''; });
      setTimeout(tick, 1000);
    };
    setTimeout(tick, 1000);
  }

  // Grab the source pixels for a shot. For a live camera we ask ImageCapture
  // for a real still — browsers that support it hand back the full sensor
  // resolution, not the 1080p preview frame. Falling back to the video frame
  // would silently cap every shot at video resolution, which is the difference
  // between a camera and a screenshot of one.
  async function capturePhoto() {
    const g = state.guide;
    const srcEl = state.live ? state.video : state.image;
    if (!srcEl) return null;

    if (state.live && window.ImageCapture && state.lastStream) {
      const track = state.lastStream.getVideoTracks()[0];
      if (track) {
        try {
          const ic = new ImageCapture(track);
          const blob = await ic.takePhoto();
          const bmp = await createImageBitmap(blob);
          let W = bmp.width, H = bmp.height;
          // the guide already carries the orientation decision, so crop to the
          // guide's own shape rather than re-deriving it from the label
          const ar = g.w / g.h;
          // crop to the guide's ratio
          let cw = W, ch = W / ar;
          if (ch > H) { ch = H; cw = H * ar; }
          const c = document.createElement('canvas');
          c.width = Math.round(cw); c.height = Math.round(ch);
          const cx = c.getContext('2d', { willReadFrequently: true });
          cx.drawImage(bmp, (W - cw) / 2, (H - ch) / 2, cw, ch, 0, 0, c.width, c.height);
          bmp.close && bmp.close();
          return {
            img: cx.getImageData(0, 0, c.width, c.height),
            W: c.width, H: c.height,
            source: 'sensor',
          };
        } catch (err) {
          // permission revoked mid-session, or the device refuses stills —
          // a degraded shot beats no shot
        }
      }
    }

    // photo mode, or a camera that will not do stills: frame from the source
    const sw = state.live ? state.video.videoWidth : state.image.naturalWidth;
    const sh = state.live ? state.video.videoHeight : state.image.naturalHeight;
    const ar = g.w / g.h;
    let W = Math.min(1800, Math.round(Math.min(sw, sh * ar)));
    let H = Math.round(W / ar);
    if (H > sh) { H = sh; W = Math.round(H * ar); }
    const img = captureRegion(W, H);
    return img ? { img, W, H, source: state.live ? 'preview' : 'photo' } : null;
  }

  async function fire() {
    const taken = await capturePhoto();
    if (!taken) { toast('Nothing to shoot yet'); return; }
    const { img, W, H, source } = taken;

    const f = $('flash');
    f.classList.add('fire');
    setTimeout(() => f.classList.remove('fire'), 360);

    const r = activeRecipe();
    const out = wctx.createImageData(W, H);
    const kelvin = estimateSceneKelvin(img.data);
    const t0 = performance.now();
    render(img, out, r, {
      sceneKelvin: kelvin, exposure: state.exposure, grainSeed: 11, lut: activeLUT(r),
    });
    const ms = Math.round(performance.now() - t0);

    const cap = document.createElement('canvas');
    cap.width = W; cap.height = H;
    cap.getContext('2d').putImageData(out, 0, 0);
    state.lastShot = { canvas: cap, name: r.name };

    const sc = $('shot');
    sc.width = W; sc.height = H;
    sc.getContext('2d').putImageData(out, 0, 0);
    $('shotMeta').textContent =
      `${r.name}\n${simLabel(r)} · ${W}×${H} · ${ms} ms · ${source}`;
    $('review').hidden = false;
    $('btnGallery').style.backgroundImage = `url(${cap.toDataURL('image/jpeg', 0.6)})`;
    $('btnGallery').style.backgroundSize = 'cover';
  }

  async function saveShot() {
    if (!state.lastShot) return;
    const { canvas, name } = state.lastShot;
    const file = `recipe-cam_${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}.jpg`;

    // Inside the Android shell a blob <a download> silently does nothing, so
    // write the file and hand it to the system share sheet — that is where
    // "Save to Photos" lives. In a browser, the download path is correct.
    const plugins = (window.Capacitor && window.Capacitor.Plugins) || null;
    if (plugins && plugins.Filesystem) {
      try {
        const b64 = canvas.toDataURL('image/jpeg', 0.94).split(',')[1];
        const res = await plugins.Filesystem.writeFile({
          path: file, data: b64, directory: 'CACHE', recursive: true,
        });
        if (plugins.Share) {
          await plugins.Share.share({ title: name, files: [res.uri] });
          toast('Shared ' + file);
        } else {
          toast('Saved to app storage');
        }
        return;
      } catch (err) {
        if (err && err.message && /cancel/i.test(err.message)) return;   // user backed out
        // fall through to the browser path rather than losing the shot
      }
    }

    canvas.toBlob((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = file; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 3000);
      toast('Saved ' + file);
    }, 'image/jpeg', 0.94);
  }

  // ── sheets ──────────────────────────────────────────────────
  const SHEETS = { recipe: 'sheetRecipe', tune: 'sheetTune', menu: 'sheetMenu', info: 'sheetInfo' };

  function openSheet(kind) {
    closeSheets(true);
    state.sheet = kind;
    const el = $(SHEETS[kind]);
    if (kind === 'recipe') buildRecipeGrid();
    if (kind === 'tune') buildTune();
    if (kind === 'menu') buildMenu();
    if (kind === 'info') buildInfo();
    $('backdrop').hidden = false;
    el.hidden = false;
    $('btnRecipe').classList.toggle('open', kind === 'recipe');
  }

  function closeSheets(keepBackdrop) {
    for (const id of Object.values(SHEETS)) $(id).hidden = true;
    state.sheet = null;
    $('btnRecipe').classList.remove('open');
    if (!keepBackdrop) $('backdrop').hidden = true;
  }

  function buildInfo() {
    $('infoBody').innerHTML = `
      <div class="card" style="padding:15px">
        <div class="info-block">
          <h4>Shoot the look, don't edit it</h4>
          <p>Each recipe is a Fujifilm film-simulation recipe — the same parameters as the
          camera's IQ menu. Pick one, shoot, and the finished frame already looks that way.</p>
        </div>
        <div class="info-block">
          <h4>Two colour bases</h4>
          <p><b>Fuji LUT</b> runs Fujifilm's own published film-simulation LUTs, rebuilt for
          ordinary images by composing sRGB → linear → BT.2020 → F-Log2 → Fuji LUT → BT.709.
          <b>Model</b> is a hand-built approximation, so you can see the difference.</p>
        </div>
        <div class="info-block">
          <h4>What it doesn't claim</h4>
          <p>It renders <i>looks</i>. No X-Trans sensor, no clean RAW in a browser — fine
          detail, Acros grain and exact Color Chrome are approximations.</p>
        </div>
        <div class="info-block" style="margin-bottom:0">
          <h4>Try</h4>
          <p>Drag the exposure lens. Hold the frame to see the original. <b>Space</b> shoots,
          <b>←/→</b> change recipe, <b>G</b> grid, <b>C</b> compare.</p>
        </div>
      </div>`;
  }

  // ── toast ───────────────────────────────────────────────────
  let toastTimer = null;
  function toast(msg, ms) {
    const el = $('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), ms || 2100);
  }

  // ── compare ─────────────────────────────────────────────────
  function setCompare(on) {
    if (state.comparing === on) return;
    state.comparing = on;
    // comparing needs the untouched frame, which the shader does not produce —
    // route that one case through the CPU
    if (on && glr) { const t = glr; glr = false; renderView(); glr = t; }
    else renderView();
  }

  // ── wiring ──────────────────────────────────────────────────
  function wire() {
    $('btnShoot').addEventListener('click', shoot);
    $('btnSave').addEventListener('click', saveShot);
    $('btnDiscard').addEventListener('click', () => { $('review').hidden = true; });
    $('btnGallery').addEventListener('click', () => {
      if (state.lastShot) $('review').hidden = false;
      else toast('Nothing shot yet');
    });

    $('btnRecipe').addEventListener('click', () =>
      state.sheet === 'recipe' ? closeSheets() : openSheet('recipe'));
    $('btnMenu').addEventListener('click', () =>
      state.sheet === 'menu' ? closeSheets() : openSheet('menu'));
    $('btnEditCurrent').addEventListener('click', () => openSheet('tune'));
    $('btnOpenInfo').addEventListener('click', () => openSheet('info'));
    $('backdrop').addEventListener('click', () => closeSheets());
    for (const b of document.querySelectorAll('[data-close]')) {
      b.addEventListener('click', () => closeSheets());
    }

    // lift-on-hold for any .lg (the Glisse gesture)
    for (const el of document.querySelectorAll('.lg')) {
      el.addEventListener('pointerdown', () => el.classList.add('lift'));
      const off = () => el.classList.remove('lift');
      el.addEventListener('pointerup', off);
      el.addEventListener('pointercancel', off);
      el.addEventListener('pointerleave', off);
    }

    // zoom
    $('zoom').addEventListener('click', (e) => {
      const b = e.target.closest('.zoom-btn');
      if (b) setZoom(Number(b.dataset.zoom));
    });

    // a refused stream is usually fixable by trying again after the user has
    // granted the permission in system settings — so let the frame retry
    $('app').addEventListener('click', (e) => {
      if (e.target.closest('button, .lens-rail, .sheet, .backdrop, .review, .zoom, .topbar')) return;
      if (state.camState === 'denied' || state.camState === 'unavailable') openCamera();
    });

    // hold frame to compare
    $('app').addEventListener('pointerdown', (e) => {
      if (e.target.closest('button, .lens-rail, .sheet, .backdrop, .review, .zoom, .topbar')) return;
      setCompare(true);
    });
    $('app').addEventListener('pointerup', () => setCompare(false));
    $('app').addEventListener('pointercancel', () => setCompare(false));

    // photo
    $('file').addEventListener('change', async (e) => {
      const f = e.target.files && e.target.files[0];
      if (!f) return;

      // ── RAW: TIFF/DNG route. A JPEG has the camera's recipe already baked in;
      //    a raw file is the only input where "recipe on the sensor data" is
      //    literally true, so it gets the dedicated decoder.
      if (/\.(tif|tiff|dng|raw)$/i.test(f.name)) {
        try {
          toast('Decoding ' + f.name + '…', 4000);
          const buf = await f.arrayBuffer();
          const decoded = decodeRaw(buf);
          const img2 = toImageData(decoded, 2000);
          const c = document.createElement('canvas');
          c.width = img2.width; c.height = img2.height;
          c.getContext('2d').putImageData(new ImageData(img2.data, img2.width, img2.height), 0, 0);
          const rawImg = new Image();
          rawImg.onload = () => {
            state.image = rawImg;
            state.rawMeta = decoded.meta;
            if (state.live) stopLive();
            state.sceneKelvin = kelvinFrom(rawImg);
            setMode('RAW', true);
            layout(); scheduleThumbs();
            toast(`${decoded.meta.note} — recipe applied to raw`, 4200);
          };
          rawImg.src = c.toDataURL('image/png');
          return;
        } catch (err) {
          toast(String(err.message || err), 6000);
          return;
        }
      }

      const url = URL.createObjectURL(f);
      const img = new Image();
      img.onload = () => {
        state.image = img;
        if (state.live) stopLive();
        state.sceneKelvin = kelvinFrom(img);
        setMode('PHOTO', true);
        renderView(); scheduleThumbs();
        toast('Photo loaded — recipes apply live');
      };
      img.onerror = () => toast('Could not read that image');
      img.src = url;
    });

    wireExposure();

    window.addEventListener('keydown', (e) => {
      if (e.key === ' ') { e.preventDefault(); shoot(); }
      if (e.key === 'Escape') closeSheets();
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        const i = SEEK.findIndex((r) => r.id === state.recipeId);
        const n = (i + (e.key === 'ArrowRight' ? 1 : SEEK.length - 1)) % SEEK.length;
        chooseRecipe(SEEK[n].id);
      }
      if (e.key === 'c' || e.key === 'C') setCompare(true);
      if (e.key === 'g' || e.key === 'G') {
        state.grid = !state.grid;
        $('grid').classList.toggle('on', state.grid);
      }
    });
    window.addEventListener('keyup', (e) => {
      if (e.key === 'c' || e.key === 'C') setCompare(false);
    });

    // A camera app must let go of the sensor when it is not on screen, or
    // Android hands the next app a black preview.
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        if (state.live) { stopLive(); state.wasLive = true; }
      } else if (state.wasLive) {
        state.wasLive = false;
        openCamera();
      }
    });
    window.addEventListener('beforeunload', () => { if (state.live) stopLive(); });

    window.addEventListener('resize', layout);
    window.addEventListener('orientationchange', () => setTimeout(layout, 250));
  }

  function kelvinFrom(img) {
    const c = document.createElement('canvas');
    const w = 200, h = Math.max(2, Math.round((img.naturalHeight / img.naturalWidth) * 200));
    c.width = w; c.height = h;
    const cx = c.getContext('2d', { willReadFrequently: true });
    cx.drawImage(img, 0, 0, w, h);
    return estimateSceneKelvin(cx.getImageData(0, 0, w, h).data);
  }

  // ── boot ────────────────────────────────────────────────────
  function boot() {
    wire();
    state.lutsLoaded = loadEmbeddedLUTs();
    $('hist').hidden = !state.histogram;

    const img = new Image();
    img.onload = () => {
      state.image = img;
      state.sceneKelvin = kelvinFrom(img);
      $('rbName').textContent = baseRecipe().name;
      setMode('SAMPLE', true);
      initGL();               // first: layout() sizes the buffer from the path
      layout();
      scheduleThumbs();
      setExposure(0);
      updateShutterState();
      // A camera app opens on the camera. If the platform refuses, the sample
      // frame stays so the app is still usable rather than a black screen.
      openCamera();
    };
    img.src = IMAGE_DATA;
  }

  boot();
})();
