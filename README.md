# Keyboard with Pinterest

An Android keyboard built on top of [HeliBoard](https://github.com/HeliBorg/HeliBoard) with an integrated **Mini Pinterest Drawer** that lets you browse Pinterest, search pins, and send high-resolution images, GIFs, or videos directly into your active chat or prompt box without leaving the keyboard.

---

## Highlights & Pinterest Integration

- **Inline Mini Pinterest Drawer**
  - Open Pinterest directly inside the keyboard from the toolbar strip (or quick-access toolbar button).
  - Persistent cookie & login session — sign in once and stay signed in across apps and restarts.
  - Built-in expandable drawer height (Normal / Expanded / Typing mode) with back, home, send-pin, and close controls.
- **Direct-to-Chat Sending Across Apps**
  - Tap Pinterest's native **`••• -> Download`** menu inside the drawer, or tap the toolbar **Send Current Pin** button, to grab the full-resolution media (`i.pinimg.com/originals` when available, with automatic fallback) and send it directly into the active app.
  - Uses Android's `InputConnectionCompat.commitContent` when supported by the target app and falls back seamlessly to `ACTION_SEND` for apps like WhatsApp, Instagram, ChatGPT, Gemini, and more.
- **In-Drawer Mini Keyboard & Google Account Helper**
  - Focused search bars, comments, and email/password fields inside the drawer bring up an isolated in-drawer mini keyboard with clipboard paste and a native Android Google account chooser helper (`PinterestAuthActivity`).
- **Built-in Memory & Speed Optimizations**
  - **Idle & Low-Memory Auto-Sleep:** Automatically unloads the underlying WebView after 3 minutes of inactivity or on Android `onTrimMemory`, while preserving login cookies and your last visited URL.
  - **Streaming Media Transfers:** Streams downloaded pins directly to temporary app cache (`shared_pins/`) instead of buffering large media blobs in RAM.
  - **Bounded Blob Cache:** Caps in-page JavaScript `Blob` references to 3 entries and evicts sent blobs immediately.
  - **Cached Mini Keyboard Layouts:** Reuses the mini keyboard layout set across openings until locale or geometry changes.
- **Optional Manual App Updater**
  - Never forces updates. Check for new releases manually anytime under **Settings -> About -> Check for updates**, view release notes, and optionally download & install the APK directly from GitHub Releases.

---

## Inherits Everything from HeliBoard

This project is forked from and built upon the open-source **[HeliBoard](https://github.com/HeliBorg/HeliBoard)** codebase, keeping its core privacy-friendly AOSP-derived typing engine, customizable themes, multilingual layouts, glide typing support (optional proprietary library), clipboard history, one-handed/split modes, and gesture controls.

---

## Building from Source

Requires JDK 17 and the Android SDK (`compileSdk 37`, `minSdk 21`):

```bash
# Build universal debug APK (includes all ABIs for broad device compatibility)
./gradlew assembleDebug
```

Generated APK path:
`app/build/outputs/apk/debug/HeliBoard_4.3-pin1-debug.apk`

---

## License & Attribution

- Licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see [LICENSE](LICENSE), along with [LICENSE-Apache-2.0](LICENSE-Apache-2.0) and [LICENSE-CC-BY-SA-4.0](LICENSE-CC-BY-SA-4.0).
- Forked from **[HeliBorg/HeliBoard](https://github.com/HeliBorg/HeliBoard)** (originally built by [Helium314](https://github.com/Helium314) and contributors, derived from AOSP LatinIME and OpenBoard).
