## Grain Photo vVERSION — A Guided Nine-Shot 112 MP Mode (`Android`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.8.5

The phone has no native 100 MP mode to switch on — the camera report from v0.8.3 proved it — so
Grain now makes one: a **guided nine-shot merge** that resolves the same field of view at about
**112 megapixels**. Nine frames give nine samples of every original pixel, which is exactly what a
**3×3 grid of sub-pixel phases** needs, and the screen now shows you that grid filling up as the
shots land.

#### 1. HI-RES 3 112MP — the new drive mode
- **New:** `HI-RES 3 112MP` on the **DRIVE** dial. It shoots **nine** RAW frames of the **same**
  scene and merges them into roughly **`12216 × 9164` (~112 MP)** — three times the linear
  resolution of the phone's own 12.5 MP sensor, from the **same field of view**. Nothing is cropped
  and nothing is panned: turn the phone sideways and the file is a landscape picture, because the
  orientation is in the EXIF.
- **The exposure is now hard-locked** for the whole sequence. Nine frames averaged together
  cannot afford the brightness moving between them, so the exposure is held from the first frame
  to the last (this also fixes the four-shot modes).
- The merge uses **one** working buffer instead of two, so a 112 MP merge needs about half the
  memory the old design needed, and the merge can no longer overflow its own accumulator at nine
  frames.

#### 2. The phase guide — a 3×3 grid that fills up as you shoot
- **New:** while the nine shots are being taken, the screen shows a **3×3 grid**. It is not the
  picture — it is the **phase space of one original sensor pixel**: each cell is one of the nine
  sub-pixel positions a frame can land on. A cell lights up as the frame that landed on it is
  measured.
- **New:** a **drift meter** under the grid shows how far the last frame moved from the first
  (green under 0.7 px, amber to 1.5 px, red beyond) and **corner ticks** mark the frame edges, so
  you can see at a glance that the phone has not moved.
- **New:** the shot counter shows `1/9` … `9/9`, and the first shot asks you to **put the phone on
  a tripod and hold it steady**.

#### 3. It will not run out of memory
- A 112 MP merge needs roughly **670 MB** (the merged RAW buffer plus the picture the film
  developer paints). Grain now **checks what the phone can actually spare before it starts** and
  merges smaller if it has to, instead of dying half way through the sequence. The receipt says
  which size was really merged.

#### 4. Everything Else From v0.8.4 Still Here
- The camera report can be **saved as a .txt file**, and the short high-resolution summary can be
  copied in one tap.
- The manual-focus scale under the screen, engraved with the real distances the lens reports.
