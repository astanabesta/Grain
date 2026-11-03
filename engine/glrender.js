// glrender.js — WebGL2 renderer for the recipe pipeline, with the CPU engine as
// a verified fallback. If a device cannot do 3D textures, or the shader fails to
// compile, every frame silently falls back rather than showing a black screen.

import { VERT, FRAG } from './gl.js';

export function createGLRenderer(canvas) {
  const gl = canvas.getContext('webgl2', {
    alpha: false, antialias: false, depth: false, stencil: false,
    preserveDrawingBuffer: true, powerPreference: 'high-performance',
  });
  if (!gl) return null;

  const compile = (type, src) => {
    const sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      throw new Error('shader: ' + gl.getShaderInfoLog(sh));
    }
    return sh;
  };

  let prog;
  try {
    prog = gl.createProgram();
    gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT));
    gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG));
    gl.bindAttribLocation(prog, 0, 'a_pos');
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      throw new Error('link: ' + gl.getProgramInfoLog(prog));
    }
  } catch (err) {
    console.warn('[gl] falling back to CPU:', err.message);
    return null;
  }
  gl.useProgram(prog);

  // full-screen triangle pair
  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

  const srcTex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, srcTex);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

  const lutTex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_3D, lutTex);
  gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_R, gl.CLAMP_TO_EDGE);

  const U = {};
  for (const n of ['u_src', 'u_lut', 'u_hasLut', 'u_texel', 'u_gain', 'u_expMul', 'u_dr',
                   'u_hl', 'u_sh', 'u_mono', 'u_monoW', 'u_colorSat', 'u_cc', 'u_ccb', 'u_clear']) {
    U[n] = gl.getUniformLocation(prog, n);
  }
  gl.uniform1i(U.u_src, 0);
  gl.uniform1i(U.u_lut, 1);

  let lutKey = null;
  let uploaded = false;

  // Upload a 33^3 8-bit LUT (red-fastest) as a 3D texture. The 8-bit grid keeps
  // the upload at 108 KB per sim and the hardware interpolates for free.
  function setLUT(bytes, slug) {
    if (!bytes) { gl.uniform1i(U.u_hasLut, 0); lutKey = null; return; }
    if (lutKey === slug) { gl.uniform1i(U.u_hasLut, 1); return; }
    const N = 33;
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_3D, lutTex);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGB8, N, N, N, 0, gl.RGB, gl.UNSIGNED_BYTE, bytes);
    gl.uniform1i(U.u_hasLut, 1);
    lutKey = slug;
    uploaded = true;
  }

  const MONO_W = {
    none: [0.28, 0.62, 0.10], red: [0.62, 0.32, 0.06],
    green: [0.16, 0.76, 0.08], yellow: [0.42, 0.50, 0.08],
  };
  const DR = { 100: 0, 200: 0.18, 400: 0.34, auto: 0.24 };

  /**
   * Render `source` (a <video> or <canvas>) through `recipe` into the canvas.
   * `gains` and `sceneKelvin` come from the caller so the CPU fallback and this
   * path agree exactly.
   */
  function draw(source, recipe, opts = {}) {
    const W = canvas.width, H = canvas.height;
    gl.viewport(0, 0, W, H);

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, srcTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);

    setLUT(opts.lut || null, opts.lutSlug || null);

    const g = opts.gains || [1, 1, 1];
    gl.uniform3f(U.u_gain, g[0], g[1], g[2]);
    gl.uniform1f(U.u_expMul, Math.pow(2, (recipe.ev || 0) + (opts.exposure || 0)));
    gl.uniform1f(U.u_dr, DR[recipe.dynamicRange] ?? 0.24);
    gl.uniform1f(U.u_hl, recipe.highlight || 0);
    gl.uniform1f(U.u_sh, recipe.shadow || 0);
    gl.uniform2f(U.u_texel, 1 / Math.max(1, W), 1 / Math.max(1, H));
    gl.uniform1f(U.u_colorSat, 1 + (recipe.color || 0) * 0.065);
    gl.uniform1f(U.u_cc, recipe.colorChrome === 'strong' ? 0.55 : recipe.colorChrome === 'weak' ? 0.28 : 0);
    gl.uniform1f(U.u_ccb, recipe.colorChromeBlue === 'strong' ? 0.55 : recipe.colorChromeBlue === 'weak' ? 0.28 : 0);
    // Clarity costs four extra texture fetches per pixel — more than everything
    // else in the shader combined. At preview scale it is imperceptible, so the
    // live path skips it and only the full-resolution render applies it.
    gl.uniform1f(U.u_clear, opts.preview ? 0 : (recipe.clarity || 0));

    const mono = !!opts.mono;
    gl.uniform1i(U.u_mono, mono ? 1 : 0);
    const w = MONO_W[opts.monoFilter || 'none'] || MONO_W.none;
    gl.uniform3f(U.u_monoW, w[0], w[1], w[2]);

    gl.drawArrays(gl.TRIANGLES, 0, 6);
    return true;
  }

  return { draw, gl, hasUploadedLUT: () => uploaded };
}
