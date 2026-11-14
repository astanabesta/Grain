package dev.grain.photo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression guard for the v0.9.38 crash: GLSL ES 3.00 has no default precision for sampler3D in
 * fragment shaders, so a bare {@code uniform sampler3D uLut;} fails to compile on strict drivers
 * (Xiaomi/Adreno S0032) — from GLThread, which kills the whole process.
 */
public class LivePreviewRendererShaderTest {

  @Test
  public void samplersWithoutDefaultPrecisionAreExplicitlyQualified() {
    String src = LivePreviewRenderer.FRAGMENT;
    assertTrue(src.startsWith("#version 300 es\n"));
    assertFalse("sampler3D has no ESSL3 default precision", src.contains("uniform sampler3D"));
    assertFalse(
        "qualify the OES sampler too; some drivers have no default for it either",
        src.contains("uniform samplerExternalOES"));
    assertTrue(src.contains("uniform highp sampler3D uLut"));
    assertTrue(src.contains("uniform highp samplerExternalOES uTex"));
  }
}
