## Grain Photo vVERSION — working Shutter/ISO dials, Bright Kodak, Flash, first-install intro & Xiaomi HyperOS updater fix (device-test beta)

- **Updating from v0.6.0 on Xiaomi / Redmi / POCO (HyperOS / MIUI):** Download and install **Grain-vVERSION.apk** once below (installs directly over `v0.6.0` — your photos, LUTs, and settings stay intact). From **vVERSION** onward, future in-app updates work cleanly on HyperOS without triggering Xiaomi's silent-session `"Update cancelled"` block.

### What's new in v0.7.1

#### 1. Fixed: In-App Updater `"Update cancelled"` on Xiaomi HyperOS / MIUI
- Xiaomi HyperOS / MIUI aborts `PackageInstaller` sessions that request Android 12+ `USER_ACTION_NOT_REQUIRED` (`INSTALL_FAILED_ABORTED: Permission denied`), which `v0.6.0` reported as `"Update cancelled."`.
- **v0.7.1** adds a dedicated `FileProvider` system-installer path (`application/vnd.android.package-archive`) for Xiaomi/Redmi/POCO devices plus automatic fallback on any OEM-restricted session abort and foreground confirmation launching.

#### 2. Fixed: Shutter Speed & ISO Dial (`P` / `S` / `ISO` / `M`)
- **Live viewfinder preview:** Turning the top-left **Shutter Speed** or **ISO** drum now drives live sensor exposure in real time (`P`, `S` Shutter Priority, `ISO` Priority, and `M` Manual) instead of leaving the viewfinder on auto exposure. Slow shutter speeds (`1/4s` – `1"`) cap preview frame time at `1/8s` with proportional preview ISO boost so viewfinder brightness matches your dial while staying smooth.
- **Reliable manual still capture:** Disables Zero-Shutter-Lag (`CONTROL_ENABLE_ZSL = false`) and sets `SENSOR_FRAME_DURATION` on manual/priority stills so Android 10+ / Xiaomi / MediaTek HALs expose at your exact requested shutter time and ISO rather than returning a buffered auto-exposed ZSL frame.
- **Closed-loop priority metering & easier `[ISO]` toggle:** Tracks ambient brightness changes during `S` and `ISO` priority modes, displays distinct `P` / `S` / `ISO` / `M` mode badges + dial toasts, and expands the `[ISO]` / `SHUTTER` tap target beneath the top-left drum so finger taps never miss.

#### 3. New Film Recipe: **Bright Kodak** (`BKD`)
- Added Ritchie Roesch’s published X-Trans IV **Bright Kodak** recipe right alongside *Kodak Gold 200* on the Film dial (**`BKD`**): Classic Chrome, `DR400`, Highlight `-2`, Shadow `-2`, Color `+4`, Sharpness `-2`, High ISO NR `-4`, Clarity `-3`, Strong Large grain, Color Chrome Off, FX Blue Off, Daylight WB (`R +3, B -7`), typical exposure `+1 to +1 2/3 EV`.

#### 4. Hardware **Flash** (`⚡ Off · Auto · Fill On · Torch`)
- Added a dedicated top-plate **`⚡`** Flash button (portrait & landscape), **MENU → SHOOT → FLASH**, and OSD flash indicator.
- Runs full Camera2 `CONTROL_AE_PRECAPTURE_TRIGGER` metering before `AUTO` / `FILL ON` flash captures, supports `FLASH_MODE_SINGLE` with manual exposure, and provides continuous `TORCH` LED fill light.

#### 5. First-Install Intro & Onboarding Guide
- New full-screen 4-step welcome flow on first install covering what Grain is, what it offers, contextual camera-only permission + local privacy pledge, and live **Silver / Black / Graphite** camera body finish selection before opening the shutter.
- Replayable anytime from **MENU → SET UP → INTRO GUIDE**.

Signed with the project's personal key (certificate SHA-256 `6e073ef6…`). `SHA256SUMS.txt` verifies the APK download.
