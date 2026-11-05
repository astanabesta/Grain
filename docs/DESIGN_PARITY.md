# Native UI aligned to the final HTML reference

Reference: `design/grain-prototype.html` — the final framing edition, not the first tabbed design.

## Implemented in native UI code

- Warm paper `#F4F1E8`, olive controls `#303C29`, restrained orange `#E76736`.
- Serif Grain wordmark with a separately colored orange dot, serif sheet/gallery headings, small technical labels.
- Compact timer/settings header with consistent drawn line icons, not platform-dependent emoji glyphs.
- Dominant live viewfinder with framing corners, discreet film badge, grid/ratio controls at top and exposure/zoom below.
- One combined zoom capsule with 1x, current zoom/readout, and 2x.
- Dark anchored in-viewfinder popovers for ratio, precision zoom and exposure. Removed stock AlertDialogs for these tools.
- Ratio picker uses three actual shape diagrams and Classic / Square / Story labels.
- Selected olive film chip with a small dot; no redundant bottom navigation.
- Paper-bordered, slightly rotated last-shot thumbnail with a count badge and Photos label.
- Custom double-ring orange shutter with line-art aperture, pressed feedback, countdown and busy states.
- Line-art flip control and label.
- Hold-neutral comparison at lower left and outlined Tune film / strength pill at lower right.
- Film sheet has a drag handle, close circle, horizontal selected cards, image artwork, serif names, description, strength slider, Reset and Back to shooting.
- Full-screen gallery and preferences panels have the HTML's heading/eyebrow, fine divider, circular close control and quiet paper surfaces.
- Settings use custom labeled rows with olive toggles, section labels, pale note card and no giant stock form dialog.
- Empty gallery has the tilted-camera-paper illustration, serif first-frame message and olive action pill.
- Polaroid-style gallery cards, dark Save action, capture receipt, and actionable Undo toast remain wired to native app functions.

## Intentional production differences

- Native app uses the real camera, system status/navigation bars and phone screen. It does not draw a fake phone frame, fake Dynamic Island, desktop marketing copy, or a sample image behind the shutter.
- Four implemented ISP recipes are shown; the unimplemented Silver recipe is not a fake active option.
- Film-card travel imagery is collection/reference artwork only. Thumbnail color tints are illustrative and never applied to captured photos. The card area explicitly says these are not exact recipe previews. The real live preview is above the sheet.
- Compare says **Hold neutral**, not “original”, because this capture path cannot provide a literal unfiltered original preview.
- Retained-source copy is called **full-frame recipe capture**, not “unfiltered original”.
- RAW/HEIF/grain/shutter-sound controls are not displayed as working features when they are not implemented.
- Device diagnostics/permission prompts may still use standard Android dialogs; normal camera framing/film/settings interactions use Grain's own surfaces.

## Verification boundary

Native layout/component code has been updated and syntax checked. There is no local Android SDK, emulator, APK, or physical-device screenshot in this workspace. Pixel-level spacing, font metrics, status-bar appearance and touch ergonomics require the first CI APK and a screenshot from the Redmi. The HTML remains the interactive design target, not fabricated evidence of a running native build.
