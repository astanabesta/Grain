## Grain Photo vVERSION — A Startup Failure Should Show Itself, Not Just Die (`Android`)

- **Android (`Grain-vVERSION.apk`):** Install directly inside Grain (**MENU → SET UP → APP UPDATE**) or download **`Grain-vVERSION.apk`** below.
- **iOS (`Grain-iOS-vVERSION.ipa` — Free Sideload):** Download **`Grain-iOS-vVERSION.ipa`** below and install onto any iPhone (iOS 16+, including **iPhone XR / XS** and newer) using **[Sideloadly](https://sideloadly.io/)**, **[AltStore](https://altstore.io/)**, or **[SideStore](https://sidestore.io/)**.

### What's new in v0.8.6

**v0.8.5** (the guided nine-shot 112 MP mode) was reported to crash when opened on the Redmi Note
13 Pro+. This release does not guess at the cause. It makes the failure **visible and survivable**,
and it puts the reason where it can be read **even when the app never opens**.

#### 1. The app no longer dies silently at startup
- **New:** if something goes wrong while Grain is opening, the reason is shown in a dialog with a
  **Copy report** button — and the app **stays open** instead of vanishing into "Grain keeps
  stopping".
- The crash report now appears **half a second** after launch instead of 1.2 s, so even a crash in
  the very first second still reports itself.

#### 2. Every crash now leaves a file on the phone
- **New:** each crash also writes **`Grain-crash-<date>.txt`** into **`Download/Grain`**. If the app
  cannot open at all, the reason is still a file you can pick up and send — no app needed.

#### 3. The update check comes much earlier
- The check for a new release now starts almost immediately after launch, so a build that dies a
  second after opening can still be replaced **from inside the app**.

#### 4. If Grain still crashes, how to get this release
- Download **`Grain-vVERSION.apk`** below and open it. Android asks once to allow installs from
  your browser. It is signed with the same key and carries a higher version number, so it installs
  **straight over** the crashing build — no uninstall, no lost photos.
