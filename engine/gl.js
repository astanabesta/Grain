// gl.js — the recipe pipeline on the GPU.
//
// The CPU engine (color.js) renders ~9 fps at preview size; a phone camera needs
// 30-60. The recipe is almost entirely a *pointwise* transform — for every input
// pixel it computes an output pixel with no knowledge of its neighbours — which
// is exactly what a fragment shader is for. The 33^3 Fujifilm LUT goes into a 3D
// texture and the sampler does the trilinear interpolation for free.
//
// Only three stages are spatial (clarity, sharpness, grain) and they stay on the
// CPU or are dropped in the preview.
//
// Order matters and follows the camera:
//   sRGB -> linear -> WB gains + exposure -> dynamic-range shoulder
//        -> tone curve (highlight/shadow) -> mono filter (before the LUT)
//        -> LUT (base simulation) -> Color -> Color Chrome -> clamp

export const VERT = `#version 300 es
in vec2 a_pos;
out vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}`;

export const FRAG = `#version 300 es
precision highp float;
precision highp sampler3D;

in vec2 v_uv;
out vec4 outColor;

uniform sampler2D u_src;
uniform sampler3D u_lut;
uniform bool  u_hasLut;
uniform vec2  u_texel;        // 1 / source size

uniform vec3  u_gain;         // white balance gains, applied in linear light
uniform float u_expMul;       // 2^(ev + exposure)
uniform float u_dr;           // dynamic-range shoulder strength
uniform float u_hl;           // highlight, -4..4
uniform float u_sh;           // shadow,    -4..4
uniform bool  u_mono;
uniform vec3  u_monoW;        // mono channel weights
uniform float u_colorSat;     // 1 + color*0.065
uniform float u_cc;           // colour chrome amount
uniform float u_ccb;          // colour chrome blue amount
uniform float u_clear;        // clarity, -5..5  (spatial, 3x3 approximation)

vec3 srgbToLinear(vec3 c) {
  return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}
vec3 linearToSrgb(vec3 c) {
  c = max(c, 0.0);
  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

// DR is a highlight compressor: above the knee, tones come out darker.
float shoulder(float x, float amt) {
  if (amt <= 0.0 || x <= 1e-6) return x;
  const float knee = 0.60;
  if (x <= knee) return x;
  float over = (x - knee) / (1.0 - knee);
  float rolled = 1.0 - pow(1.0 - over, 1.0 / (1.0 + amt * 2.4));
  return knee + (1.0 - knee) * rolled;
}

float bell(float x, float c, float w) {
  float d = (x - c) / w;
  return exp(-d * d);
}

float toneCurve(float x, float hl, float sh) {
  float a = (hl / 4.0) * 0.11;
  float b = (sh / 4.0) * 0.11;
  float y = x + a * bell(x, 0.74, 0.30) - b * bell(x, 0.26, 0.30);
  return clamp(y, 0.0, 1.35);
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
  vec3 c = texture(u_src, v_uv).rgb;

  // ── linear light: white balance + exposure ────────────────────────────
  vec3 lin = srgbToLinear(c) * u_gain * u_expMul;

  // ── back to gamma space for the tone stages (as the camera does) ──────
  vec3 g = linearToSrgb(lin);

  // ── dynamic range shoulder, then the tone curve ───────────────────────
  g = vec3(shoulder(g.r, u_dr), shoulder(g.g, u_dr), shoulder(g.b, u_dr));
  g = vec3(toneCurve(g.r, u_hl, u_sh),
           toneCurve(g.g, u_hl, u_sh),
           toneCurve(g.b, u_hl, u_sh));

  // ── clarity: a cheap local-contrast pass on luma ──────────────────────
  if (abs(u_clear) > 0.001) {
    float y0 = luma(g);
    float yb = 0.0;
    yb += luma(texture(u_src, v_uv + vec2(-u_texel.x * 2.0, 0.0)).rgb);
    yb += luma(texture(u_src, v_uv + vec2(u_texel.x * 2.0, 0.0)).rgb);
    yb += luma(texture(u_src, v_uv + vec2(0.0, -u_texel.y * 2.0)).rgb);
    yb += luma(texture(u_src, v_uv + vec2(0.0, u_texel.y * 2.0)).rgb);
    yb *= 0.25;
    float k = (u_clear / 5.0) * 0.34;
    // clamped, or near-black pixels explode their chroma (the CPU engine had
    // exactly this bug and it turned shadows neon)
    float sc = y0 > 0.02 ? clamp((y0 + (y0 - yb) * k) / y0, 0.0, 4.0) : 1.0;
    g *= sc;
    g = clamp(g, 0.0, 1.35);
  }

  // ── mono colour filter, applied BEFORE the LUT (camera order) ─────────
  if (u_mono) {
    float m = dot(g, u_monoW);
    g = vec3(m);
  }

  // ── the base simulation ───────────────────────────────────────────────
  vec3 o = u_hasLut ? clamp(texture(u_lut, clamp(g, 0.0, 1.0)).rgb, 0.0, 1.0) : g;

  // ── colour depth ──────────────────────────────────────────────────────
  float y = luma(o);
  if (u_cc > 0.001 || u_ccb > 0.001) {
    float mx = max(max(o.r, o.g), o.b);
    float mn = min(min(o.r, o.g), o.b);
    float sat = mx > 1e-5 ? (mx - mn) / mx : 0.0;
    float boost = 1.0 + u_cc * sat * 0.55;
    o = y + (o - y) * boost;
    if (u_ccb > 0.0) {
      float blueW = max(0.0, o.b - (o.r + o.g) * 0.5);
      o.b += blueW * u_ccb * 0.30;
      o.g -= blueW * u_ccb * 0.06;
    }
    y = luma(o);
  }
  o = clamp(y + (o - y) * u_colorSat, 0.0, 1.0);

  outColor = vec4(o, 1.0);
}`;
